"""
Ollama Cloud backend — swaps the locally-hosted (CPU-only) Ollama models in
main_server.py for hosted cloud models via https://ollama.com, using the exact
same `ollama` Python client and /api/chat message shape as the local pipeline.

Docs: https://docs.ollama.com/cloud

SETUP:
1. Create an API key at https://ollama.com/settings/keys
2. Paste it below as OLLAMA_CLOUD_API_KEY, or set the OLLAMA_API_KEY
   environment variable instead — the environment variable takes precedence
   if both are set.
3. Adjust CLOUD_VISION_MODEL / CLOUD_TEXT_MODEL to whichever cloud model you
   want. Browse the current catalog at https://ollama.com/search?c=cloud —
   CLOUD_VISION_MODEL must be a vision-capable model.
"""
from pathlib import Path

import os

from ollama import Client

# Paste your Ollama Cloud API key here, or set the OLLAMA_API_KEY environment
# variable (which takes precedence if set). https://ollama.com/settings/keys
OLLAMA_CLOUD_API_KEY = "OLLAMA API KEY"

# Any model name from https://ollama.com/search?c=cloud. CLOUD_VISION_MODEL
# must support image input.
CLOUD_VISION_MODEL = "gemma4:31b"
CLOUD_TEXT_MODEL = "nemotron-3-nano:30b"


def _cloud_client() -> Client:
    api_key = os.environ.get("OLLAMA_API_KEY") or OLLAMA_CLOUD_API_KEY
    if not api_key or api_key == "your_api_key_here":
        raise RuntimeError(
            "Ollama Cloud API key not configured. Set OLLAMA_CLOUD_API_KEY in "
            "backend/cloud_server.py, or set the OLLAMA_API_KEY environment "
            "variable, then restart the backend."
        )
    return Client(
        host="https://ollama.com",
        headers={"Authorization": f"Bearer {api_key}"},
    )


def analyze_image_cloud(image_path: Path, instruction: str, json_schema: dict) -> str:
    """Same input/output contract as main_server.py's local vision-direct call:
    one image + one instruction in, raw JSON string out. No keep_alive here —
    there's no local model weight to keep resident on a remote host."""
    client = _cloud_client()
    response = client.chat(
        model=CLOUD_VISION_MODEL,
        format=json_schema,
        messages=[{
            "role": "user",
            "content": instruction,
            "images": [image_path],
        }],
        options={"temperature": 0},
    )
    return response.message.content


def run_llm_chat_cloud(messages: list) -> str:
    """Same input/output contract as main_server.py's local run_llm_chat: the full
    conversation in, assistant text out. The caller still owns appending the
    user/assistant turns to session['messages']."""
    client = _cloud_client()
    response = client.chat(
        model=CLOUD_TEXT_MODEL,
        messages=messages,
        options={"temperature": 0.2},
    )
    return response.message.content


def rank_fda_match_cloud(messages: list, json_schema: dict) -> str:
    """Same shape as analyze_image_cloud but text-only (no image) — used by fda_matcher.py's
    ranking step to pick an FDA product code from a small pre-shortlisted candidate list."""
    client = _cloud_client()
    response = client.chat(
        model=CLOUD_TEXT_MODEL,
        format=json_schema,
        messages=messages,
        options={"temperature": 0},
    )
    return response.message.content
