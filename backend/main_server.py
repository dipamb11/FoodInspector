from pathlib import Path
from typing import Literal
from uuid import uuid4
import html
import json
import re
import shutil
import socket
import os
import sys
import platform
import time

try:
    import fastapi
except ImportError:
    fastapi = None

from fastapi.concurrency import run_in_threadpool
from faster_whisper import WhisperModel
from fastapi import FastAPI, UploadFile, File, HTTPException, Request, Form
from fastapi.responses import HTMLResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, ValidationError
from datetime import datetime
from ollama import chat
from PIL import Image, ImageOps

import cloud_server
import fda_matcher

class LabelAnalysis(BaseModel):
    """
    One detected food/product in the image.
    A separate object is returned for every distinct food package/product.
    """
    product_id: int = Field(
        description="1-based sequential ID for this detected food/product."
    )
    product_name: str | None = None
    brand_name: str | None = None
    lot_number: str | None = None
    expiration_date: str | None = None
    manufacturer: str | None = None
    ingredients: list[str] = Field(default_factory=list)
    allergens: list[str] = Field(default_factory=list)
    nutrition_claims: list[str] = Field(default_factory=list)
    visible_text: str | None = None
    # Grounded candidate-generation only (see fda_matcher.py) — populated as a post-processing
    # step after extraction, never requested from the vision/OCR extraction prompts themselves.
    fda_match: fda_matcher.FdaMatch = Field(default_factory=fda_matcher.FdaMatch)


class MultiFoodAnalysis(BaseModel):
    """
    The complete image analysis. Each item in foods represents one
    distinct food/product visible in the image.
    """
    foods: list[LabelAnalysis] = Field(
        default_factory=list,
        description=(
            "One entry per distinct food/product visible in the image. "
            "Do not merge different products."
        ),
    )

app = FastAPI(title="AI-assisted Image Analysis")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

UPLOAD_DIR = Path("uploads")
UPLOAD_DIR.mkdir(exist_ok=True)

#OLLAMA_MODEL = "minicpm-v4.6:1b"
VISION_MODEL = "ministral-3:3b"

# Start with the same Ollama model if you don't have
# a separate text model yet.
TEXT_MODEL = "minicpm-v4.6:1b"

# Purpose-built OCR model used by the "ocr_first" pipeline (see PIPELINE_MODE
# below) to transcribe label text cheaply before any structuring reasoning
# happens, instead of asking a full vision-reasoning model to both read and
# structure the image in one expensive pass.
OCR_MODEL = "glm-ocr:bf16"

# Model used for fda_matcher.py's ranking call. Tested both locally: TEXT_MODEL
# (minicpm-v4.6:1b) reliably garbled the ranking schema's JSON output (truncated
# mid-string), while VISION_MODEL (ministral-3:3b) handled the exact same
# prompt/schema cleanly — used here purely as a capable text model, no image
# involved. Kept as its own constant in case that changes with a future model swap.
FDA_RANKING_MODEL = VISION_MODEL

# "ocr_first": OCR_MODEL transcribes visible text, then TEXT_MODEL structures
#   that text into MultiFoodAnalysis JSON — no image tokens in the structuring
#   step, which is where most of the CPU time in the old pipeline went.
# "vision_direct": the original behavior — VISION_MODEL sees the image and
#   produces structured JSON directly in one call. Kept as an easy revert if
#   OCR quality turns out worse on some label.
PIPELINE_MODE = "vision_direct"

WHISPER_MODEL = "small"
WHISPER_DEVICE = "cpu"
WHISPER_COMPUTE_TYPE = "int8"

# This machine has no GPU, so every Ollama call is CPU-bound. Two big, avoidable
# costs on top of raw inference: (1) Ollama's default keep_alive (~5 min) unloads
# a model between requests, so the next request pays full weight-load time again;
# (2) vision models here report a huge default context length (e.g. ministral-3:3b
# defaults to 262144) that Ollama sizes/allocates for even on a short prompt.
OLLAMA_KEEP_ALIVE = "30m"
OLLAMA_NUM_CTX = 4096

print("Loading Whisper model...")

whisper_model = WhisperModel(
    WHISPER_MODEL,
    device=WHISPER_DEVICE,
    compute_type=WHISPER_COMPUTE_TYPE,
)

print("Whisper model loaded.")


def _warm_up_ollama_models() -> None:
    """Fires one throwaway chat() call per model used by the active pipeline so the
    weight-load cost happens once at server boot, not on the user's first request.
    Mirrors what InspectMeta's docs tell a presenter to do manually ("say a
    throwaway 'inspect test' first") — this just does it automatically at startup."""
    # FDA_RANKING_MODEL == VISION_MODEL today, so this list has no duplicate warm-up cost in
    # practice — but included explicitly (dict.fromkeys below de-dupes) so FDA matching never
    # pays a surprise cold-load on its first use under ocr_first mode, where VISION_MODEL
    # otherwise wouldn't be warmed at all.
    models_to_warm = (
        [TEXT_MODEL, OCR_MODEL, FDA_RANKING_MODEL] if PIPELINE_MODE == "ocr_first"
        else [VISION_MODEL, TEXT_MODEL, FDA_RANKING_MODEL]
    )
    for model in dict.fromkeys(models_to_warm):  # de-dupe, preserve order
        try:
            start = time.perf_counter()
            chat(
                model=model,
                messages=[{"role": "user", "content": "Reply with OK only."}],
                options={"temperature": 0, "num_ctx": OLLAMA_NUM_CTX},
                keep_alive=OLLAMA_KEEP_ALIVE,
            )
            print(f"Warmed up Ollama model '{model}' in {time.perf_counter() - start:.1f}s")
        except Exception as e:
            print(f"Warm-up failed for model '{model}': {e}")


@app.on_event("startup")
def warm_up_models_on_startup() -> None:
    _warm_up_ollama_models()


MAX_IMAGE_DIMENSION = 1024
JPEG_QUALITY = 80

# Shared by the local "vision_direct" pipeline and the Ollama Cloud pipeline —
# both send the whole image straight to a vision-reasoning model in one call,
# just against a different Ollama Client (local vs. cloud_server.py's remote one).
VISION_DIRECT_INSTRUCTION = (
    "Analyze the entire image and identify EVERY DISTINCT FOOD PRODUCT OR "
    "FOOD PACKAGE that is visibly present. The image can contain one product "
    "or multiple products.\n\n"
    "IMPORTANT MULTI-PRODUCT RULES:\n"
    "1. First scan the complete image and count the distinct food products.\n"
    "2. Create exactly ONE foods array entry for EACH distinct food/product.\n"
    "3. Never merge two different packages/products into one entry.\n"
    "4. If the same product appears multiple times as separate visible packages, "
    "create a separate entry for each visible package.\n"
    "5. Assign product_id values sequentially starting at 1.\n"
    "6. Extract text and label information separately for each product.\n"
    "7. Do not copy information from one product into another.\n"
    "8. Ignore unrelated non-food objects.\n"
    "9. If no food product can be identified, return an empty foods array.\n\n"
    "FOR EACH FOOD PRODUCT, EXTRACT:\n"
    "- product_name\n"
    "- brand_name\n"
    "- lot_number\n"
    "- expiration_date\n"
    "- manufacturer\n"
    "- ingredients\n"
    "- allergens\n"
    "- nutrition_claims\n"
    "- all clearly visible text\n\n"
    "If a field is not visible or cannot be determined, use null for strings "
    "and an empty list for lists. Do not guess. "
    "Return ONLY valid JSON matching the provided schema."
)

_JSON_CODE_FENCE_RE = re.compile(r"^```(?:json)?\s*|\s*```\s*$", re.IGNORECASE)


def strip_json_code_fence(raw: str) -> str:
    """Some models (observed with Ollama Cloud's gemma4:31b) ignore the `format` JSON-schema
    constraint and wrap their JSON reply in a ```json ... ``` markdown fence anyway, which
    breaks model_validate_json outright. Local models with grammar-constrained decoding don't
    do this, but stripping unconditionally is harmless either way."""
    return _JSON_CODE_FENCE_RE.sub("", raw.strip()) if raw else raw


def extract_json_payload(raw: str) -> str:
    """More general safety net than strip_json_code_fence: some cloud responses add stray
    prose before/after the JSON ("Here's the analysis:\\n{...}\\nLet me know if...") that a
    plain fence-strip doesn't catch, and it fails model_validate_json with a formatting error
    even though the JSON itself is well-formed. Scans for the first '{' or '[' and returns
    everything up to its matching close bracket (tracking string literals so braces inside
    quoted text, e.g. ingredient names, don't throw off the depth count) — i.e. exactly the
    top-level JSON value, discarding whatever surrounds it."""
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
    return raw[start:]  # unbalanced brackets — best-effort from the first one found


def normalize_multi_food_json(raw: str) -> str:
    """Some models (observed with Ollama Cloud's gemma4:31b) ignore the `format` JSON-schema
    constraint's top-level shape and return a bare JSON array of products (`[ {...}, {...} ]`)
    instead of the schema's required `{"foods": [...]}` object — same field-level content,
    wrong envelope, which fails model_validate_json even though the actual data is fine.
    Re-wrap a bare array before validation instead of rejecting a perfectly usable response."""
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return raw
    if isinstance(parsed, list):
        return json.dumps({"foods": parsed})
    return raw


# In-memory PoC sessions.
# Each session keeps the latest parsed image and the multi-turn conversation.
sessions: dict[str, dict] = {}

def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.2)
        s.connect(("8.8.8.8", 80))
        ip_address = s.getsockname()[0]
        s.close()
        return ip_address
    except Exception:
        return "127.0.0.1"


def preprocess_image_for_ollama(
    input_path: Path,
    output_path: Path,
    max_dimension: int = MAX_IMAGE_DIMENSION,
    jpeg_quality: int = JPEG_QUALITY
) -> dict:
    """
    Resize and compress high-resolution phone photos before sending to Ollama.
    This prevents vision context size errors.
    """

    with Image.open(input_path) as img:
        original_width, original_height = img.size

        # Respect iPhone EXIF orientation
        img = ImageOps.exif_transpose(img)

        # Convert to RGB because HEIC/PNG/RGBA can cause issues
        img = img.convert("RGB")

        # Resize while preserving aspect ratio
        img.thumbnail((max_dimension, max_dimension), Image.Resampling.LANCZOS)

        processed_width, processed_height = img.size

        # Save compressed JPEG
        img.save(
            output_path,
            format="JPEG",
            quality=jpeg_quality,
            optimize=True,
            progressive=True
        )

    return {
        "original_width": original_width,
        "original_height": original_height,
        "processed_width": processed_width,
        "processed_height": processed_height,
        "processed_file_size_bytes": output_path.stat().st_size
    }

def run_ocr_pass(image_path: Path) -> str:
    """Stage 1 of the 'ocr_first' pipeline: a cheap, fast transcription of every visible
    character in the image using a model built for OCR, not general visual reasoning."""
    response = chat(
        model=OCR_MODEL,
        messages=[{
            "role": "user",
            "content": (
                "Transcribe ALL visible text in this image exactly as it appears, top to "
                "bottom, left to right. Include every label, ingredient list, nutrition "
                "fact, brand name, lot number, expiration date, and any other printed "
                "text. Do not summarize or omit anything. Do not add commentary."
            ),
            "images": [image_path],
        }],
        options={"temperature": 0, "num_ctx": OLLAMA_NUM_CTX},
        keep_alive=OLLAMA_KEEP_ALIVE,
    )
    return response.message.content or ""


def structure_ocr_text(ocr_text: str) -> str:
    """Stage 2 of the 'ocr_first' pipeline: split raw OCR text into distinct food
    products and fill the same MultiFoodAnalysis schema fields the vision-direct
    pipeline extracts — but reasoning over cheap text tokens, not image tokens."""
    instruction = (
        "Below is raw OCR-transcribed text captured from a photo of one or more food "
        "product labels. OCR text may contain minor errors, broken line breaks, or "
        "out-of-order fragments — use your judgment to reconstruct the most likely "
        "correct product information.\n\n"
        "IMPORTANT MULTI-PRODUCT RULES:\n"
        "1. First read the complete transcribed text and count the distinct food products.\n"
        "2. Create exactly ONE foods array entry for EACH distinct food/product.\n"
        "3. Never merge two different packages/products into one entry.\n"
        "4. If the same product appears multiple times as separate visible packages, "
        "create a separate entry for each visible package.\n"
        "5. Assign product_id values sequentially starting at 1.\n"
        "6. Extract text and label information separately for each product.\n"
        "7. Do not copy information from one product into another.\n"
        "8. Ignore unrelated non-food text.\n"
        "9. If no food product can be identified, return an empty foods array.\n\n"
        "FOR EACH FOOD PRODUCT, EXTRACT:\n"
        "- product_name\n- brand_name\n- lot_number\n- expiration_date\n- manufacturer\n"
        "- ingredients\n- allergens\n- nutrition_claims\n- all clearly visible text\n\n"
        "If a field is not visible or cannot be determined, use null for strings and an "
        "empty list for lists. Do not guess. Return ONLY valid JSON matching the "
        "provided schema.\n\n"
        "TRANSCRIBED TEXT:\n"
        f"{ocr_text}"
    )
    response = chat(
        model=TEXT_MODEL,
        format=MultiFoodAnalysis.model_json_schema(),
        messages=[{"role": "user", "content": instruction}],
        options={"temperature": 0, "num_ctx": OLLAMA_NUM_CTX, "num_predict": 1024},
        keep_alive=OLLAMA_KEEP_ALIVE,
    )
    return response.message.content


def transcribe_audio(audio_path: Path) -> dict:
    """
    Transcribe an audio file using local faster-whisper.
    """

    segments, info = whisper_model.transcribe(
        str(audio_path),
        beam_size=5,
        vad_filter=True,
        condition_on_previous_text=False,
    )

    # The transcription actually runs while iterating.
    segments = list(segments)

    text = " ".join(
        segment.text.strip()
        for segment in segments
        if segment.text.strip()
    ).strip()

    return {
        "text": text,
        "language": info.language,
        "language_probability": info.language_probability,
    }


def create_session() -> str:
    session_id = str(uuid4())
    sessions[session_id] = {
        "image_analysis": None,
        # Seeded immediately (not just after an image is analyzed) so voice
        # requests can be the very first action in a session, e.g. a spoken
        # capture command from the Android app before any photo exists yet.
        "messages": [{
            "role": "system",
            "content": build_system_message(None)
        }],
        # Wearable/device metadata. The actual hardware connection is owned
        # by the native Meta DAT companion application.
        "device": None,
        "device_id": None,
        "last_device_command": None,
    }
    return session_id


def get_session(session_id: str) -> dict:
    session = sessions.get(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Session not found.")
    return session


def build_system_message(image_analysis: dict | None) -> str:
    product_context = (
        json.dumps(image_analysis, ensure_ascii=False)
        if image_analysis
        else "No product image has been analyzed yet."
    )

    return (
        "You are a voice assistant that helps users understand food product labels. "
        "The current image may contain multiple distinct food products. "
        "Treat each item in the foods array as a separate product and never merge "
        "information from different products. If the user refers to 'the first', "
        "'the second', a brand, or a product name, use product_id and the extracted "
        "fields to identify the correct product. "
        "Use the structured product information extracted from the current image as "
        "the primary source for product-specific facts. "
        "Do not invent product information. If the available product information does "
        "not answer a question, say so clearly. Keep answers concise and natural because "
        "the answer may eventually be spoken aloud, and is always shown as plain text (not a "
        "markdown renderer) — so never use markdown formatting like **bold**, *italics*, "
        "`code`, or # headers, and never return a table or JSON unless the user explicitly "
        "asks for one. Just answer in plain conversational sentences.\n\n"
        "CURRENT IMAGE PRODUCT INFORMATION:\n"
        f"{product_context}"
    )

@app.get("/health")
def health():
    """Machine-readable health endpoint for browser/mobile/wearable clients."""
    return {
        "status": "ok",
        "service": "food-label-ai",
        "device_bridge": "meta-dat-compatible",
        "vision_model": VISION_MODEL,
        "text_model": TEXT_MODEL,
        "whisper_model": WHISPER_MODEL,
        "active_sessions": len(sessions),
        "timestamp": datetime.now().astimezone().isoformat(),
    }


@app.get("/debug", response_class=HTMLResponse)
def debug_page(request: Request):
    local_ip = get_local_ip()

    # Check Ollama
    ollama_status = "OK"
    ollama_error = None

    try:
        # A lightweight Ollama call using the configured vision model.
        # This confirms the Ollama Python client can communicate with Ollama.
        chat(
            model=VISION_MODEL,
            messages=[
                {
                    "role": "user",
                    "content": "Reply with OK only."
                }
            ],
            options={"temperature": 0, "num_ctx": OLLAMA_NUM_CTX},
            keep_alive=OLLAMA_KEEP_ALIVE,
        )
    except Exception as e:
        ollama_status = "ERROR"
        ollama_error = str(e)

    # Check Whisper
    whisper_status = "LOADED" if whisper_model is not None else "NOT LOADED"

    # Session information
    session_rows = ""

    for session_id, session in sessions.items():
        image_loaded = session.get("image_analysis") is not None
        message_count = len(session.get("messages", []))

        # Don't expose the complete UUID if you don't need to.
        short_id = session_id[:8]

        session_rows += f"""
        <tr>
            <td><code>{html.escape(short_id)}</code></td>
            <td>{'YES' if image_loaded else 'NO'}</td>
            <td>{message_count}</td>
        </tr>
        """

    if not session_rows:
        session_rows = """
        <tr>
            <td colspan="3">No active sessions</td>
        </tr>
        """

    ollama_error_html = ""

    if ollama_error:
        ollama_error_html = f"""
        <div class="error">
            <strong>Ollama error:</strong><br>
            {html.escape(ollama_error)}
        </div>
        """

    html_content = f"""
    <!DOCTYPE html>
    <html>
    <head>
        <meta name="viewport"
              content="width=device-width, initial-scale=1">

        <title>Food Label AI - Debug</title>

        <style>
            body {{
                font-family: Arial, sans-serif;
                max-width: 900px;
                margin: 0 auto;
                padding: 20px;
                background: #f5f5f7;
                color: #222;
            }}

            h1 {{
                margin-bottom: 5px;
            }}

            .subtitle {{
                color: #666;
                margin-bottom: 25px;
            }}

            .card {{
                background: white;
                border-radius: 14px;
                padding: 18px;
                margin-bottom: 18px;
                box-shadow: 0 1px 5px rgba(0,0,0,.08);
            }}

            .ok {{
                color: #16803c;
                font-weight: bold;
            }}

            .error {{
                color: #b42318;
                background: #fff1f0;
                padding: 12px;
                border-radius: 8px;
                margin-top: 10px;
            }}

            table {{
                width: 100%;
                border-collapse: collapse;
            }}

            th, td {{
                text-align: left;
                padding: 10px;
                border-bottom: 1px solid #ddd;
            }}

            th {{
                background: #f2f2f7;
            }}

            code {{
                background: #f2f2f7;
                padding: 3px 6px;
                border-radius: 5px;
            }}

            .value {{
                font-weight: 500;
            }}
        </style>
    </head>

    <body>

        <h1>🛠 Food Label AI Debug</h1>

        <div class="subtitle">
            Backend diagnostics and runtime information
        </div>

        <div class="card">
            <h2>Backend</h2>

            <table>
                <tr>
                    <th>Item</th>
                    <th>Value</th>
                </tr>

                <tr>
                    <td>Status</td>
                    <td class="ok">RUNNING</td>
                </tr>

                <tr>
                    <td>Server hostname</td>
                    <td>{html.escape(socket.gethostname())}</td>
                </tr>

                <tr>
                    <td>Local IP</td>
                    <td><code>{html.escape(local_ip)}</code></td>
                </tr>

                <tr>
                    <td>Request host</td>
                    <td>{html.escape(request.headers.get("host", "unknown"))}</td>
                </tr>

                <tr>
                    <td>Client IP</td>
                    <td>{html.escape(
                        request.client.host
                        if request.client
                        else "unknown"
                    )}</td>
                </tr>

                <tr>
                    <td>Current time</td>
                    <td>{datetime.now().astimezone().isoformat()}</td>
                </tr>

                <tr>
                    <td>Python</td>
                    <td>{html.escape(sys.version)}</td>
                </tr>

                <tr>
                    <td>Platform</td>
                    <td>{html.escape(platform.platform())}</td>
                </tr>

                <tr>
                    <td>FastAPI</td>
                    <td>{html.escape(
                        fastapi.__version__
                        if fastapi
                        else "unknown"
                    )}</td>
                </tr>
            </table>
        </div>

        <div class="card">
            <h2>🤖 AI Models</h2>

            <table>
                <tr>
                    <th>Component</th>
                    <th>Configuration</th>
                </tr>

                <tr>
                    <td>Vision model</td>
                    <td><code>{html.escape(VISION_MODEL)}</code></td>
                </tr>

                <tr>
                    <td>Text model</td>
                    <td><code>{html.escape(TEXT_MODEL)}</code></td>
                </tr>

                <tr>
                    <td>Whisper model</td>
                    <td><code>{html.escape(WHISPER_MODEL)}</code></td>
                </tr>

                <tr>
                    <td>Whisper device</td>
                    <td><code>{html.escape(WHISPER_DEVICE)}</code></td>
                </tr>

                <tr>
                    <td>Whisper compute type</td>
                    <td><code>{html.escape(WHISPER_COMPUTE_TYPE)}</code></td>
                </tr>

                <tr>
                    <td>Whisper status</td>
                    <td class="ok">{whisper_status}</td>
                </tr>
            </table>
        </div>

        <div class="card">
            <h2>🦙 Ollama</h2>

            <table>
                <tr>
                    <th>Item</th>
                    <th>Value</th>
                </tr>

                <tr>
                    <td>Status</td>
                    <td class="{
                        'ok' if ollama_status == 'OK'
                        else ''
                    }">
                        {ollama_status}
                    </td>
                </tr>

                <tr>
                    <td>Text model</td>
                    <td><code>{html.escape(TEXT_MODEL)}</code></td>
                </tr>

                <tr>
                    <td>Vision model</td>
                    <td><code>{html.escape(VISION_MODEL)}</code></td>
                </tr>
            </table>

            {ollama_error_html}
        </div>

        <div class="card">
            <h2>📁 Image Processing</h2>

            <table>
                <tr>
                    <th>Item</th>
                    <th>Value</th>
                </tr>

                <tr>
                    <td>Upload directory</td>
                    <td><code>{html.escape(str(UPLOAD_DIR.resolve()))}</code></td>
                </tr>

                <tr>
                    <td>Max image dimension</td>
                    <td>{MAX_IMAGE_DIMENSION}px</td>
                </tr>

                <tr>
                    <td>JPEG quality</td>
                    <td>{JPEG_QUALITY}</td>
                </tr>
            </table>
        </div>

        <div class="card">
            <h2>🥽 Meta Glasses Integration</h2>
            <table>
                <tr>
                    <th>Item</th>
                    <th>Value</th>
                </tr>
                <tr>
                    <td>Backend bridge</td>
                    <td class="ok">READY</td>
                </tr>
                <tr>
                    <td>Image endpoint</td>
                    <td><code>/session/{session_id}/glasses/image</code></td>
                </tr>
                <tr>
                    <td>Voice endpoint</td>
                    <td><code>/session/{session_id}/glasses/voice</code></td>
                </tr>
                <tr>
                    <td>Command endpoint</td>
                    <td><code>/session/{session_id}/glasses/command</code></td>
                </tr>
                <tr>
                    <td>Status endpoint</td>
                    <td><code>/session/{session_id}/glasses/status</code></td>
                </tr>
            </table>
            <p>
                The native Meta DAT companion application owns the physical
                glasses connection. This FastAPI service receives the captured
                image/audio and runs the existing AI pipeline.
            </p>
        </div>

        <div class="card">
            <h2>💬 Active Sessions</h2>

            <p>
                Active sessions:
                <strong>{len(sessions)}</strong>
            </p>

            <table>
                <tr>
                    <th>Session</th>
                    <th>Image analyzed</th>
                    <th>Messages</th>
                </tr>

                {session_rows}
            </table>
        </div>

        <div class="card">
            <h2>🌐 Useful URLs</h2>

            <table>
                <tr>
                    <th>Purpose</th>
                    <th>URL</th>
                </tr>

                <tr>
                    <td>Application</td>
                    <td>
                        <code>http://{html.escape(local_ip)}:8000/</code>
                    </td>
                </tr>

                <tr>
                    <td>Debug</td>
                    <td>
                        <code>http://{html.escape(local_ip)}:8000/debug</code>
                    </td>
                </tr>

                <tr>
                    <td>API docs</td>
                    <td>
                        <code>http://{html.escape(local_ip)}:8000/docs</code>
                    </td>
                </tr>

                <tr>
                    <td>OpenAPI JSON</td>
                    <td>
                        <code>http://{html.escape(local_ip)}:8000/openapi.json</code>
                    </td>
                </tr>
            </table>
        </div>

        <div class="card">
            <p>
                Debug page generated at:
                <strong>{datetime.now().astimezone().isoformat()}</strong>
            </p>
        </div>

    </body>
    </html>
    """

    return HTMLResponse(content=html_content)

@app.get("/", response_class=HTMLResponse)
def unified_page():
    session_id = create_session()

    page = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Food Label AI Assistant</title>
<style>
* { box-sizing: border-box; }
body {
    font-family: Arial, sans-serif;
    max-width: 760px;
    margin: 0 auto;
    padding: 16px;
    background: #f7f7f9;
}
h1 { font-size: 26px; margin-bottom: 8px; }
.subtitle { color: #666; margin-bottom: 18px; }
.card {
    background: white;
    border-radius: 16px;
    padding: 18px;
    margin-bottom: 16px;
    box-shadow: 0 1px 5px rgba(0,0,0,.08);
}
input[type=file] { width: 100%; margin: 10px 0; }
button {
    width: 100%;
    padding: 15px;
    border: 0;
    border-radius: 14px;
    font-size: 18px;
    font-weight: 600;
    cursor: pointer;
    margin-top: 8px;
}
#analyzeButton { background: #007aff; color: white; }
#recordButton { background: #111; color: white; }
#recordButton.recording { background: #ff3b30; }
button:disabled { opacity: .45; cursor: not-allowed; }
#status, #voiceStatus {
    padding: 10px 0;
    color: #555;
    font-weight: 600;
}
#analysis {
    white-space: pre-wrap;
    font-family: monospace;
    font-size: 13px;
    overflow-wrap: anywhere;
    background: #f2f2f7;
    padding: 12px;
    border-radius: 10px;
}
.chat { display: flex; flex-direction: column; gap: 10px; }
.message {
    padding: 12px 14px;
    border-radius: 14px;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
}
.user {
    align-self: flex-end;
    background: #007aff;
    color: white;
    max-width: 88%;
}
.assistant {
    align-self: flex-start;
    background: #e9e9ee;
    color: #111;
    max-width: 88%;
}
.label {
    font-size: 12px;
    font-weight: bold;
    opacity: .7;
    margin-bottom: 4px;
}
.hidden { display: none; }
.toggle-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
}
.toggle-row .toggle-text { font-weight: 600; }
.toggle-row .toggle-subtext {
    font-size: 12px;
    color: #666;
    font-weight: normal;
}
.switch {
    position: relative;
    display: inline-block;
    width: 46px;
    height: 26px;
    flex-shrink: 0;
}
.switch input { opacity: 0; width: 0; height: 0; }
.slider {
    position: absolute;
    cursor: pointer;
    inset: 0;
    background-color: #ccc;
    border-radius: 999px;
    transition: .15s;
}
.slider::before {
    position: absolute;
    content: "";
    height: 20px;
    width: 20px;
    left: 3px;
    top: 3px;
    background-color: white;
    border-radius: 50%;
    transition: .15s;
}
input:checked + .slider { background-color: #007aff; }
input:checked + .slider::before { transform: translateX(20px); }
</style>
</head>
<body>

<h1>🥫 Food Label AI Assistant</h1>
<div class="subtitle">
Take a picture of a food label, then ask multiple questions about it.
</div>

<div class="card">
<div class="toggle-row">
<div>
<div class="toggle-text">Use Cloud LLM</div>
<div class="toggle-subtext" id="backendModeSubtext">Local Ollama on this PC</div>
</div>
<label class="switch">
<input id="cloudModeToggle" type="checkbox">
<span class="slider"></span>
</label>
</div>
</div>

<div class="card">
<h2>📷 Product Label</h2>
<input id="imageFile" type="file" accept="image/*" capture="environment">
<button id="analyzeButton">Analyze Food Label</button>
<div id="status">Ready</div>

<div id="analysisContainer" class="hidden">
<h3>Analysis</h3>
<div id="analysis"></div>
</div>
</div>

<div class="card">
<h2>💬 Ask About This Product</h2>
<div id="chat" class="chat"></div>
<button id="recordButton" disabled>🎤 Start Recording</button>
<div id="voiceStatus">Analyze an image first.</div>
</div>

<script>
const sessionId = "__SESSION_ID__";

let mediaRecorder = null;
let audioChunks = [];
let stream = null;

const imageFile = document.getElementById("imageFile");
const analyzeButton = document.getElementById("analyzeButton");
const recordButton = document.getElementById("recordButton");
const statusElement = document.getElementById("status");
const voiceStatusElement = document.getElementById("voiceStatus");
const analysisContainer = document.getElementById("analysisContainer");
const analysisElement = document.getElementById("analysis");
const chatElement = document.getElementById("chat");
const cloudModeToggle = document.getElementById("cloudModeToggle");
const backendModeSubtext = document.getElementById("backendModeSubtext");

function backendMode() {
    return cloudModeToggle.checked ? "cloud" : "local";
}

cloudModeToggle.addEventListener("change", function() {
    backendModeSubtext.textContent = cloudModeToggle.checked
        ? "Ollama Cloud (see backend/cloud_server.py)"
        : "Local Ollama on this PC";
});

function getSupportedMimeType() {
    const types = [
        "audio/mp4",
        "audio/webm;codecs=opus",
        "audio/webm",
        "audio/ogg;codecs=opus"
    ];
    for (const type of types) {
        if (MediaRecorder.isTypeSupported(type)) return type;
    }
    return "";
}

function addMessage(role, text) {
    const message = document.createElement("div");
    message.className = "message " + role;

    const label = document.createElement("div");
    label.className = "label";
    label.textContent = role === "user" ? "You" : "Assistant";

    const body = document.createElement("div");
    body.textContent = text;

    message.appendChild(label);
    message.appendChild(body);
    chatElement.appendChild(message);

    window.scrollTo({
        top: document.body.scrollHeight,
        behavior: "smooth"
    });
}

async function analyzeImage() {
    const file = imageFile.files[0];

    if (!file) {
        statusElement.textContent = "Please take or select a food-label image.";
        return;
    }

    const formData = new FormData();
    formData.append("file", file);
    formData.append("backend_mode", backendMode());

    analyzeButton.disabled = true;
    recordButton.disabled = true;
    statusElement.textContent = "Analyzing image...";

    try {
        const response = await fetch(
            "/session/" + sessionId + "/image",
            { method: "POST", body: formData }
        );

        const data = await response.json();

        if (!response.ok) {
            throw new Error(data.detail || "Image analysis failed.");
        }

        analysisElement.textContent =
            JSON.stringify(data.analysis, null, 2);

        analysisContainer.classList.remove("hidden");
        recordButton.disabled = false;

        chatElement.innerHTML = "";
        addMessage(
            "assistant",
            "I’ve analyzed the image and separated the visible food products. You can now ask me questions about any of them."
        );

        statusElement.textContent = "Image analyzed.";
        voiceStatusElement.textContent = "Ready for your question.";
    } catch (error) {
        console.error(error);
        statusElement.textContent = "Error: " + error.message;
    } finally {
        analyzeButton.disabled = false;
    }
}

async function startRecording() {
    try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true });

        const mimeType = getSupportedMimeType();
        mediaRecorder = mimeType
            ? new MediaRecorder(stream, { mimeType })
            : new MediaRecorder(stream);

        audioChunks = [];

        mediaRecorder.ondataavailable = function(event) {
            if (event.data.size > 0) audioChunks.push(event.data);
        };

        mediaRecorder.onstop = async function() {
            const actualMimeType = mediaRecorder.mimeType || "audio/webm";
            const audioBlob = new Blob(audioChunks, { type: actualMimeType });

            await sendAudio(audioBlob);

            if (stream) {
                stream.getTracks().forEach(track => track.stop());
                stream = null;
            }
        };

        mediaRecorder.start();
        recordButton.textContent = "⏹ Stop Recording";
        recordButton.classList.add("recording");
        voiceStatusElement.textContent = "Listening...";
    } catch (error) {
        console.error(error);
        voiceStatusElement.textContent =
            "Microphone error: " + error.message;
    }
}

function stopRecording() {
    if (mediaRecorder && mediaRecorder.state !== "inactive") {
        mediaRecorder.stop();
    }

    recordButton.textContent = "🎤 Start Recording";
    recordButton.classList.remove("recording");
    recordButton.disabled = true;
    voiceStatusElement.textContent = "Transcribing and thinking...";
}

async function sendAudio(audioBlob) {
    const formData = new FormData();

    let extension = "webm";
    if (audioBlob.type.includes("mp4")) extension = "mp4";
    else if (audioBlob.type.includes("ogg")) extension = "ogg";
    else if (audioBlob.type.includes("wav")) extension = "wav";

    formData.append("audio", audioBlob, "voice." + extension);
    formData.append("backend_mode", backendMode());

    try {
        const response = await fetch(
            "/session/" + sessionId + "/voice",
            { method: "POST", body: formData }
        );

        const data = await response.json();

        if (!response.ok) {
            throw new Error(data.detail || "Voice request failed.");
        }

        if (data.transcription.text) {
            addMessage("user", data.transcription.text);
        }

        if (data.response) {
            addMessage("assistant", data.response);
        }

        voiceStatusElement.textContent = "Ready for your next question.";
    } catch (error) {
        console.error(error);
        voiceStatusElement.textContent = "Error: " + error.message;
    } finally {
        recordButton.disabled = false;
        recordButton.textContent = "🎤 Start Recording";
        recordButton.classList.remove("recording");
    }
}

analyzeButton.addEventListener("click", analyzeImage);

recordButton.addEventListener("click", function() {
    if (mediaRecorder && mediaRecorder.state === "recording") {
        stopRecording();
    } else {
        startRecording();
    }
});
</script>
</body>
</html>
"""

    return HTMLResponse(content=page.replace("__SESSION_ID__", session_id))


@app.post("/session")
async def create_session_endpoint():
    return {"status": "success", "session_id": create_session()}


@app.post("/session/{session_id}/image")
async def analyze_session_image(
    session_id: str,
    file: UploadFile = File(...),
    backend_mode: str = Form("local"),
):
    session = get_session(session_id)

    if not file.content_type or not file.content_type.startswith("image/"):
        raise HTTPException(
            status_code=400,
            detail="Uploaded file must be an image."
        )

    original_path = UPLOAD_DIR / f"{uuid4()}_original"
    processed_path = UPLOAD_DIR / f"{uuid4()}_processed.jpg"

    try:
        with original_path.open("wb") as buffer:
            shutil.copyfileobj(file.file, buffer)

        preprocessing_info = preprocess_image_for_ollama(
            input_path=original_path,
            output_path=processed_path
        )

        stage_timings: dict[str, float] = {}
        effective_pipeline = f"cloud:{cloud_server.CLOUD_VISION_MODEL}" if backend_mode == "cloud" else PIPELINE_MODE

        async def run_vision_direct() -> str:
            t = time.perf_counter()
            resp = await run_in_threadpool(
                chat,
                model=VISION_MODEL,
                format=MultiFoodAnalysis.model_json_schema(),
                messages=[{
                    "role": "user",
                    "content": VISION_DIRECT_INSTRUCTION,
                    "images": [processed_path],
                }],
                options={"temperature": 0, "num_ctx": OLLAMA_NUM_CTX},
                keep_alive=OLLAMA_KEEP_ALIVE,
            )
            stage_timings["vision_seconds"] = round(time.perf_counter() - t, 2)
            return resp.message.content

        if backend_mode == "cloud":
            # Cloud runs on hosted GPU, not this machine's CPU, so none of the local
            # resize/recompress reasons apply — send the original upload as-is (whatever
            # resolution/quality the app captured) instead of the downscaled/JPEG-80
            # processed_path used for local models, which was losing label detail the
            # cloud model could otherwise read.
            t0 = time.perf_counter()
            raw_content = await run_in_threadpool(
                cloud_server.analyze_image_cloud,
                original_path,
                VISION_DIRECT_INSTRUCTION,
                MultiFoodAnalysis.model_json_schema(),
            )
            stage_timings["cloud_vision_seconds"] = round(time.perf_counter() - t0, 2)
        elif PIPELINE_MODE == "ocr_first":
            t0 = time.perf_counter()
            ocr_text = await run_in_threadpool(run_ocr_pass, processed_path)
            stage_timings["ocr_seconds"] = round(time.perf_counter() - t0, 2)
            print(f"[{session_id[:8]}] OCR text ({len(ocr_text)} chars): {ocr_text[:300]!r}")

            # A real glasses capture (blur, glare, off-angle framing) can leave the OCR pass
            # with little or nothing to transcribe — feeding that into the structuring step
            # just produces a validly-shaped but empty MultiFoodAnalysis (foods: []), which
            # reads to the user as "it returned nothing" even though no error occurred. The
            # old single-call vision-direct pipeline never had this failure mode because the
            # reasoning model saw the actual pixels and could still infer something even from
            # a rough photo. So: fall back to vision_direct instead of trusting empty OCR text.
            if len(ocr_text.strip()) < 15:
                print(f"[{session_id[:8]}] OCR text too short/empty — falling back to vision_direct")
                raw_content = await run_vision_direct()
                effective_pipeline = f"{PIPELINE_MODE}->vision_direct_fallback"
            else:
                t1 = time.perf_counter()
                raw_content = await run_in_threadpool(structure_ocr_text, ocr_text)
                stage_timings["structuring_seconds"] = round(time.perf_counter() - t1, 2)
        else:
            raw_content = await run_vision_direct()

        print(f"[{session_id[:8]}] image analysis ({effective_pipeline}) stage timings: {stage_timings}")
        print(f"[{session_id[:8]}] raw model response ({len(raw_content or '')} chars): {(raw_content or '')[:300]!r}")

        raw_content = extract_json_payload(raw_content)
        raw_content = strip_json_code_fence(raw_content)
        raw_content = normalize_multi_food_json(raw_content)

        try:
            image_analysis = MultiFoodAnalysis.model_validate_json(raw_content)
        except ValidationError as ve:
            # Full response goes to the server console for debugging — the client only gets a
            # short message. A phone screen (or TTS) reading out a whole JSON schema dump isn't
            # useful to the person holding it; that detail belongs in logs, not the UI.
            print(f"[{session_id[:8]}] JSON validation FAILED, full raw response: {raw_content!r}")
            raise HTTPException(
                status_code=502,
                detail={"message": "Image analysis failed.", "pipeline_mode": effective_pipeline}
            )

        analysis_dict = image_analysis.model_dump()

        # Same empty-result problem can happen on the vision_direct/cloud paths too (a genuinely
        # unreadable photo) — surfaced here as a log line rather than a silent 0-food response,
        # since a 200 with an empty foods array is valid but easy to miss in the app UI.
        if not analysis_dict.get("foods"):
            print(f"[{session_id[:8]}] WARNING: analysis produced zero foods (pipeline={effective_pipeline})")

        # Normalize product IDs in case a small vision model repeats/skips IDs.
        # The array position is authoritative for the API/UI.
        for index, food in enumerate(analysis_dict.get("foods", []), start=1):
            food["product_id"] = index

        # Grounded FDA product-code matching (see fda_matcher.py) — a post-processing step,
        # not something the extraction prompt above was asked to do carefully. Whatever the
        # extraction model put in the schema's fda_match slot (usually just nulls) is
        # unconditionally overwritten here with the real candidate-matched result.
        for food in analysis_dict.get("foods", []):
            food["fda_match"] = await fda_matcher.match_food(
                food,
                backend_mode,
                text_model=FDA_RANKING_MODEL,
                keep_alive=OLLAMA_KEEP_ALIVE,
                num_ctx=OLLAMA_NUM_CTX,
            )

        # New product image = new conversation.
        session["image_analysis"] = analysis_dict
        session["messages"] = [{
            "role": "system",
            "content": build_system_message(analysis_dict)
        }]

        return {
            "status": "success",
            "session_id": session_id,
            "preprocessing": preprocessing_info,
            "food_count": len(analysis_dict.get("foods", [])),
            "analysis": analysis_dict,
            "backend_mode": backend_mode,
            "pipeline_mode": effective_pipeline,
            "stage_timings": stage_timings,
        }

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Image analysis failed: {str(e)}"
        )
    finally:
        if original_path.exists():
            original_path.unlink()
        if processed_path.exists():
            processed_path.unlink()



# ---------------------------------------------------------------------------
# Meta Glasses / Wearable Integration
# ---------------------------------------------------------------------------
# The Meta DAT mobile app acts as the hardware bridge. It captures a photo
# from the glasses and POSTs the JPEG to this endpoint. The AI pipeline below
# remains the same as the normal browser image endpoint.
#
# Expected client flow:
#   Meta glasses -> iOS/Android DAT app -> POST /glasses/image -> this server
#
# The mobile app should send:
#   multipart/form-data
#   field name: "file"
#   content type: image/jpeg
#
@app.post("/session/{session_id}/glasses/image")
async def analyze_glasses_image(
    session_id: str,
    file: UploadFile = File(...)
):
    """
    Analyze a photo captured by Meta glasses.

    This intentionally reuses the same image-analysis endpoint so the
    browser/phone camera and Meta glasses produce identical session state.
    """
    return await analyze_session_image(session_id=session_id, file=file)


@app.post("/session/{session_id}/glasses/voice")
async def glasses_voice_command(
    session_id: str,
    audio: UploadFile = File(...)
):
    """
    Process voice captured by the Meta glasses microphone.

    The Meta mobile app can POST recorded audio here. The response contains
    the transcription and the assistant answer, which the mobile app can
    subsequently speak through the glasses.
    """
    return await session_voice_command(session_id=session_id, audio=audio)


class GlassesCommand(BaseModel):
    """
    Optional command envelope for a native Meta DAT companion app.

    action:
      - capture_image: client should capture a glasses photo and upload it
      - ask: client should send recorded audio to /glasses/voice
      - status: client can use this as a connectivity/session heartbeat
    """
    action: Literal["capture_image", "ask", "status"]
    device_id: str | None = None
    request_id: str | None = None


@app.post("/session/{session_id}/glasses/command")
async def glasses_command(
    session_id: str,
    command: GlassesCommand
):
    """
    Lightweight command endpoint for the native glasses bridge.

    IMPORTANT:
    The FastAPI server cannot directly trigger the physical Meta camera.
    It tells the companion app what should happen; the DAT app performs
    the actual camera/microphone operation.
    """
    session = get_session(session_id)

    device_id = command.device_id
    if device_id:
        session["device_id"] = device_id
    session["device"] = "meta_glasses"
    session["last_device_command"] = {
        "action": command.action,
        "request_id": command.request_id,
        "timestamp": datetime.now().astimezone().isoformat(),
    }

    if command.action == "capture_image":
        return {
            "status": "success",
            "action": "capture_image",
            "instruction": "Capture one photo from the Meta glasses and POST it to "
                           f"/session/{session_id}/glasses/image.",
            "session_id": session_id,
            "request_id": command.request_id,
        }

    if command.action == "ask":
        return {
            "status": "success",
            "action": "ask",
            "instruction": "Record the user's voice with the Meta glasses and POST "
                           f"the audio to /session/{session_id}/glasses/voice.",
            "session_id": session_id,
            "request_id": command.request_id,
        }

    return {
        "status": "success",
        "action": "status",
        "session_id": session_id,
        "device": session.get("device"),
        "device_id": session.get("device_id"),
        "image_analyzed": session.get("image_analysis") is not None,
        "message_count": len(session.get("messages", [])),
    }


@app.get("/session/{session_id}/glasses/status")
async def glasses_status(session_id: str):
    """Return the backend-side wearable/session state."""
    session = get_session(session_id)

    return {
        "status": "success",
        "session_id": session_id,
        "device": session.get("device"),
        "device_id": session.get("device_id"),
        "image_analyzed": session.get("image_analysis") is not None,
        "food_count": (
            len(session["image_analysis"].get("foods", []))
            if session.get("image_analysis")
            else 0
        ),
        "message_count": len(session.get("messages", [])),
        "last_device_command": session.get("last_device_command"),
    }


async def run_llm_chat(session: dict, user_text: str, backend_mode: str = "local") -> str:
    """Append a user turn, run the text model over the full conversation, and append the
    answer. Shared by the audio-transcription voice endpoint and the text-only ask endpoint
    so both paths keep the same multi-turn conversation behavior."""
    session["messages"].append({
        "role": "user",
        "content": user_text
    })

    if backend_mode == "cloud":
        answer = await run_in_threadpool(cloud_server.run_llm_chat_cloud, session["messages"])
    else:
        llm_response = await run_in_threadpool(
            chat,
            model=TEXT_MODEL,
            messages=session["messages"],
            options={"temperature": 0.2, "num_ctx": OLLAMA_NUM_CTX},
            keep_alive=OLLAMA_KEEP_ALIVE,
        )
        answer = llm_response.message.content

    session["messages"].append({
        "role": "assistant",
        "content": answer
    })

    return answer


class AskRequest(BaseModel):
    question: str
    backend_mode: str = "local"


@app.post("/session/{session_id}/ask")
async def session_ask(session_id: str, body: AskRequest):
    """
    Text-only counterpart to /session/{session_id}/voice, for clients that already have a
    transcript (e.g. on-device speech recognition) and don't need server-side Whisper
    transcription for every turn.
    """
    session = get_session(session_id)

    question = body.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="Question text is empty.")

    try:
        answer = await run_llm_chat(session, question, body.backend_mode)
        return {"status": "success", "response": answer}
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Ask request failed: {str(e)}"
        )


@app.post("/session/{session_id}/voice")
async def session_voice_command(
    session_id: str,
    audio: UploadFile = File(...),
    backend_mode: str = Form("local"),
):
    session = get_session(session_id)

    if not audio.content_type:
        raise HTTPException(
            status_code=400,
            detail="Audio content type is missing."
        )

    if not audio.content_type.startswith("audio/"):
        raise HTTPException(
            status_code=400,
            detail=f"Expected audio file, got {audio.content_type}"
        )

    extension = ".webm"
    if "mp4" in audio.content_type:
        extension = ".mp4"
    elif "mpeg" in audio.content_type:
        extension = ".mp3"
    elif "wav" in audio.content_type:
        extension = ".wav"
    elif "ogg" in audio.content_type:
        extension = ".ogg"

    audio_path = UPLOAD_DIR / f"{uuid4()}_voice{extension}"

    try:
        with audio_path.open("wb") as buffer:
            shutil.copyfileobj(audio.file, buffer)

        transcription = await run_in_threadpool(
            transcribe_audio,
            audio_path
        )

        transcript = transcription["text"]

        if not transcript:
            return {
                "status": "success",
                "transcription": transcription,
                "response": "I didn't hear a voice command."
            }

        answer = await run_llm_chat(session, transcript, backend_mode)

        return {
            "status": "success",
            "transcription": transcription,
            "response": answer
        }

    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Voice processing failed: {str(e)}"
        )
    finally:
        if audio_path.exists():
            audio_path.unlink()
