"""
FDA product-code candidate matching — a post-processing step over an already-structured food
item, not a change to the vision/OCR extraction prompts. Grounded candidate-generation only:
never invents a code, only ever selects from (or declines) a small pre-approved reference table.

SETUP: populate fda_food_codes.json (see its own placeholder comment) with real entries from
FDA's Product Code Builder tool. Until then, this module is a safe no-op — every food item
just comes back with verification_required=true and no matches.
"""
import json
import re
from pathlib import Path

from fastapi.concurrency import run_in_threadpool
from ollama import chat
from pydantic import BaseModel, Field

import cloud_server

REFERENCE_TABLE_PATH = Path(__file__).parent / "fda_food_codes.json"


class FdaMatch(BaseModel):
    """The field embedded on each LabelAnalysis item in main_server.py — deliberately just these
    4 fields, nothing more. NOT the schema requested from the ranking LLM call directly — see
    _RankingResult below for why."""
    selected_match: str | None = None
    confidence: float | None = None
    other_matches: list[str] = Field(default_factory=list)
    verification_required: bool = True


class _RankingResult(BaseModel):
    """What we actually ask the ranking LLM to produce — deliberately smaller than FdaMatch.
    A first pass using FdaMatch's full shape (nested arrays, several types) directly as the
    schema broke down in testing with the small local TEXT_MODEL: it garbled the JSON,
    stuffing most of its answer into the first string field instead of separate keys. Asking
    for less (one string reason instead of two string arrays) fixed that. FdaMatch itself is
    then assembled in code from this plus the candidate list we already know, not trusted
    wholesale from the model — including a hard check that selected_code is actually one of
    the offered candidates, never whatever the model felt like returning."""
    selected_code: str | None = None
    confidence: float | None = None
    reasoning: str | None = None


# --- Small JSON-cleanup helpers, duplicated from main_server.py rather than imported: main_server.py
# imports this module at startup, so importing back would be a circular import that fails
# before main_server's own module-level names exist yet. Keep in sync if the originals change. ---
_JSON_CODE_FENCE_RE = re.compile(r"^```(?:json)?\s*|\s*```\s*$", re.IGNORECASE)


def _strip_json_code_fence(raw: str) -> str:
    return _JSON_CODE_FENCE_RE.sub("", raw.strip()) if raw else raw


def _extract_json_payload(raw: str) -> str:
    if not raw:
        return raw
    start = next((i for i, ch in enumerate(raw) if ch in "{["), None)
    if start is None:
        return raw
    depth = 0
    in_string = False
    escape = False
    for i in range(start, len(raw)):
        ch = raw[i]
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch in "{[":
            depth += 1
        elif ch in "}]":
            depth -= 1
            if depth == 0:
                return raw[start:i + 1]
    return raw[start:]


def load_reference_table() -> list[dict]:
    try:
        table = json.loads(REFERENCE_TABLE_PATH.read_text(encoding="utf-8"))
        if not isinstance(table, list):
            raise ValueError("fda_food_codes.json must contain a JSON array")
        if not table:
            print(f"fda_matcher: {REFERENCE_TABLE_PATH.name} is empty — FDA code matching is a no-op until it's populated")
        return table
    except FileNotFoundError:
        print(f"fda_matcher: {REFERENCE_TABLE_PATH.name} not found — FDA code matching is a no-op until it's created")
        return []
    except Exception as e:
        print(f"fda_matcher: failed to load {REFERENCE_TABLE_PATH.name}: {e} — FDA code matching disabled")
        return []


_REFERENCE_TABLE = load_reference_table()


def find_candidates(food: dict, max_candidates: int = 3) -> list[dict]:
    """Deterministic, no LLM involved — case-insensitive alias matching against the reference
    table using whatever the extraction pipeline already pulled out of the label."""
    haystack = " ".join(
        str(v) for v in [
            food.get("product_name"),
            food.get("brand_name"),
            *(food.get("ingredients") or []),
            food.get("visible_text"),
        ] if v
    ).lower()
    if not haystack.strip():
        return []

    scored = []
    for entry in _REFERENCE_TABLE:
        # Word-boundary match, not plain substring: plain "in" containment let short aliases
        # like "cola" spuriously match inside unrelated words (e.g. "ch[ocola]te"), which got
        # noisier as more single-word aliases were added for broader single-word coverage.
        score = sum(
            1 for alias in entry.get("aliases", [])
            if re.search(rf"\b{re.escape(alias.lower())}\b", haystack)
        )
        if score > 0:
            scored.append((score, entry))
    scored.sort(key=lambda pair: -pair[0])
    return [entry for _, entry in scored[:max_candidates]]


def _build_ranking_prompt(food: dict, candidates: list[dict]) -> str:
    candidate_lines = "\n".join(
        f'- code "{c.get("product_code")}": category "{c.get("category", "")}", '
        f'known aliases: {", ".join(c.get("aliases", []))}'
        for c in candidates
    )
    return (
        "You are matching a food product to an FDA product code from a small pre-approved "
        "candidate list. Do NOT invent a code that isn't in the list below. If none of the "
        "candidates genuinely fit the product, set selected_code to null.\n\n"
        "PRODUCT:\n"
        f"- name: {food.get('product_name')}\n"
        f"- brand: {food.get('brand_name')}\n"
        f"- ingredients: {', '.join(food.get('ingredients') or [])}\n"
        f"- visible text: {food.get('visible_text')}\n\n"
        f"CANDIDATE CODES:\n{candidate_lines}\n\n"
        "Return JSON matching the schema: selected_code (one of the candidate codes above "
        "verbatim, or null if none genuinely fit), confidence (0.0-1.0), reasoning (one short "
        "sentence explaining the pick, or why none fit)."
    )


async def rank_candidates(
    food: dict,
    candidates: list[dict],
    backend_mode: str,
    text_model: str,
    keep_alive: str,
    num_ctx: int,
) -> FdaMatch:
    prompt = _build_ranking_prompt(food, candidates)
    schema = _RankingResult.model_json_schema()

    if backend_mode == "cloud":
        raw_content = await run_in_threadpool(
            cloud_server.rank_fda_match_cloud,
            [{"role": "user", "content": prompt}],
            schema,
        )
    else:
        response = await run_in_threadpool(
            chat,
            model=text_model,
            format=schema,
            messages=[{"role": "user", "content": prompt}],
            options={"temperature": 0, "num_ctx": num_ctx},
            keep_alive=keep_alive,
        )
        raw_content = response.message.content

    cleaned = _strip_json_code_fence(_extract_json_payload(raw_content))
    ranking = _RankingResult.model_validate_json(cleaned)

    candidate_codes = [c.get("product_code") for c in candidates]
    # Groundedness enforced here, not just by prompt wording: a selection the model invented
    # instead of copying verbatim from the offered list is treated the same as no selection.
    selected = ranking.selected_code if ranking.selected_code in candidate_codes else None
    return FdaMatch(
        selected_match=selected,
        confidence=ranking.confidence if selected else None,
        other_matches=[code for code in candidate_codes if code != selected],
        verification_required=True,
    )


async def match_food(
    food: dict,
    backend_mode: str,
    text_model: str,
    keep_alive: str,
    num_ctx: int,
) -> dict:
    """Entry point — never raises. A matching failure here must not break the overall image
    analysis, so any error just falls back to an unmatched (needs_verification) result."""
    try:
        candidates = find_candidates(food)
        if not candidates:
            return FdaMatch().model_dump()
        match = await rank_candidates(food, candidates, backend_mode, text_model, keep_alive, num_ctx)
        return match.model_dump()
    except Exception as e:
        print(f"fda_matcher: matching failed for '{food.get('product_name')}': {e}")
        return FdaMatch().model_dump()
