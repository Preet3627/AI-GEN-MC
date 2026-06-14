"""
AI Bridge for Minecraft Builder Mod — Multi-Provider (v0.1.0)

Supported providers:
  - ollama    (local, no key needed)
  - openai    (api.openai.com)
  - groq      (api.groq.com)
  - xai       (api.x.ai)
  - anthropic (api.anthropic.com)
  - google    (generativelanguage.googleapis.com)
"""

import json
import logging
import os
from typing import Optional

from aiohttp import web

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ai-bridge")

# -------------------------------------------------------------------
# Provider config
# -------------------------------------------------------------------
PROVIDER_CONFIGS = {
    "ollama": {
        "base": os.getenv("OLLAMA_BASE", "http://localhost:11434"),
        "generate_path": "/api/generate",
        "models_path": "/api/tags",
        "api_type": "ollama",
    },
    "openai": {
        "base": "https://api.openai.com/v1",
        "generate_path": "/chat/completions",
        "models_path": "/models",
        "api_type": "openai",
    },
    "groq": {
        "base": "https://api.groq.com/openai/v1",
        "generate_path": "/chat/completions",
        "models_path": "/models",
        "api_type": "openai",
    },
    "xai": {
        "base": "https://api.x.ai/v1",
        "generate_path": "/chat/completions",
        "models_path": "/models",
        "api_type": "openai",
    },
    "anthropic": {
        "base": "https://api.anthropic.com/v1",
        "generate_path": "/messages",
        "models_path": "/models",
        "api_type": "anthropic",
    },
    "google": {
        "base": "https://generativelanguage.googleapis.com/v1beta",
        "generate_path": "/models/{model}:generateContent",
        "models_path": "/models",
        "api_type": "google",
    },
}

DEFAULT_PROVIDER = os.getenv("AI_PROVIDER", "ollama")
DEFAULT_MODEL = os.getenv("AI_MODEL", "gpt-oss:120b-cloud")
BRIDGE_PORT = int(os.getenv("BRIDGE_PORT", "5001"))

SYSTEM_PROMPT = (
    "You are a Minecraft AI assistant inside the game. The player requests builds, "
    "commands, or game changes. You must read the terrain block data and the player's "
    "name, then output a JSON object describing what to do.\n\n"
    "TERRAIN RULES:\n"
    "- If terrain has 'minecraft:water', build stilts/piers down to the sea floor.\n"
    "- If terrain has elevation changes, adjust foundations so builds don't float.\n"
    "- Use ONLY exact block IDs: minecraft:oak_planks, minecraft:dirt, "
    "minecraft:grass_block, minecraft:stone, minecraft:oak_log, "
    "minecraft:glass, minecraft:cobblestone, minecraft:water, "
    "minecraft:sand, minecraft:gravel, minecraft:air, minecraft:chest, "
    "minecraft:oak_sign, minecraft:command_block, "
    "minecraft:repeating_command_block, minecraft:chain_command_block, "
    "minecraft:barrier, minecraft:obsidian.\n\n"
    "OUTPUT FORMAT — JSON object with these optional arrays:\n\n"
    '1. "blocks": [{"x": rel_x, "y": rel_y, "z": rel_z, "id": "minecraft:block_id"}]\n'
    "   Place blocks. Coordinates RELATIVE to player.\n\n"
    '2. "commands": [{"cmd": "/command", "desc": "...", "confirm": true/false}]\n'
    "   Execute Minecraft commands. confirm=true for destructive/game-changing actions.\n\n"
    '3. "signs": [{"x": rel_x, "y": rel_y, "z": rel_z, '
    '"lines": ["l1","l2","l3","l4"]}]\n'
    "   Place signs with text.\n\n"
    '4. "chests": [{"x": rel_x, "y": rel_y, "z": rel_z, '
    '"items": [{"id": "minecraft:item", "count": N, "slot": N}]}]\n'
    "   Place chests with items.\n\n"
    '5. "command_blocks": [{"x": rel_x, "y": rel_y, "z": rel_z, '
    '"cmd": "/command", "mode": "IMPULSE|REPEAT|CHAIN", "conditional": false}]\n'
    "   Place command blocks.\n\n"
    '6. "shell": [{"cmd": "shell command", "confirm": true}]\n'
    "   Execute system commands (always confirm).\n\n"
    '7. "undo": {"steps": 1}\n'
    "   Undo previous actions (if player requests reverting).\n\n"
    '8. "redo": {"steps": 1}\n'
    "   Redo undone actions.\n\n"
    "CRITICAL:\n"
    "- Output ONLY valid JSON. No markdown, no conversation.\n"
    "- For big builds (10+ houses), split into 3-4 houses per batch, "
    "then copy-paste with adjusted coordinates and fill terrain gaps.\n"
    "- Coordinates in blocks/signs/chests/command_blocks are RELATIVE to player.\n"
    "- The player's name is in the prompt — use it for targeted commands."
)


# -------------------------------------------------------------------
# Helpers
# -------------------------------------------------------------------
def _clean_response(text: str) -> str:
    text = text.strip()
    if text.startswith("...") or text.startswith("```"):
        text = text.split("```")[-1]
    if "```" in text:
        text = text.split("```json")[-1].split("```")[0] if "```json" in text else text.split("```")[-1]
    return text.strip()


BLOCK_NAME_CORRECTIONS = {
    "minecraft:dirt_block": "minecraft:dirt",
    "minecraft:oak_plank": "minecraft:oak_planks",
    "minecraft:oak_wood": "minecraft:oak_log",
    "minecraft:stone_block": "minecraft:stone",
    "minecraft:grass": "minecraft:grass_block",
    "minecraft:planks": "minecraft:oak_planks",
    "minecraft:glass_pane": "minecraft:glass",
    "minecraft:cobble": "minecraft:cobblestone",
    "minecraft:wooden_planks": "minecraft:oak_planks",
}


def _fix_block_ids(obj):
    if isinstance(obj, dict):
        for key in ("id", "block"):
            if key in obj and isinstance(obj[key], str):
                bid = obj[key].split("[")[0]
                obj[key] = BLOCK_NAME_CORRECTIONS.get(bid, bid)
        for v in obj.values():
            _fix_block_ids(v)
    elif isinstance(obj, list):
        for item in obj:
            _fix_block_ids(item)


# -------------------------------------------------------------------
# Provider implementations
# -------------------------------------------------------------------

# --- Ollama ---
async def _ollama_generate(prompt: str, system: str, model: str, session, cfg: dict) -> str:
    url = f"{cfg['base']}{cfg['generate_path']}"
    payload = {"model": model, "prompt": prompt, "system": system, "stream": False, "options": {"temperature": 0.2}}
    async with session.post(url, json=payload, timeout=180) as resp:
        if resp.status != 200:
            raise RuntimeError(f"Ollama HTTP {resp.status}")
        result = await resp.json()
        return result.get("response", "")


async def _ollama_models(session, cfg: dict) -> list:
    url = f"{cfg['base']}{cfg['models_path']}"
    async with session.get(url, timeout=10) as resp:
        if resp.status != 200:
            return []
        data = await resp.json()
        return [{"name": m["name"]} for m in data.get("models", [])]


# --- OpenAI-compatible (OpenAI, Groq, xAI) ---
async def _openai_generate(prompt: str, system: str, model: str, session, cfg: dict, api_key: str) -> str:
    url = f"{cfg['base']}{cfg['generate_path']}"
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.2,
    }
    async with session.post(url, json=payload, headers=headers, timeout=180) as resp:
        if resp.status != 200:
            err = await resp.text()
            raise RuntimeError(f"OpenAI HTTP {resp.status}: {err}")
        result = await resp.json()
        return result["choices"][0]["message"]["content"]


async def _openai_models(session, cfg: dict, api_key: str) -> list:
    url = f"{cfg['base']}{cfg['models_path']}"
    headers = {"Authorization": f"Bearer {api_key}"}
    async with session.get(url, headers=headers, timeout=10) as resp:
        if resp.status != 200:
            return []
        data = await resp.json()
        return [{"name": m["id"]} for m in data.get("data", [])]


# --- Anthropic Claude ---
async def _anthropic_generate(prompt: str, system: str, model: str, session, cfg: dict, api_key: str) -> str:
    url = f"{cfg['base']}{cfg['generate_path']}"
    headers = {
        "x-api-key": api_key,
        "anthropic-version": "2023-06-01",
        "Content-Type": "application/json",
    }
    payload = {
        "model": model,
        "max_tokens": 4096,
        "system": system,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2,
    }
    async with session.post(url, json=payload, headers=headers, timeout=180) as resp:
        if resp.status != 200:
            err = await resp.text()
            raise RuntimeError(f"Anthropic HTTP {resp.status}: {err}")
        result = await resp.json()
        return result["content"][0]["text"]


_ANTHROPIC_MODELS = [
    "claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022",
    "claude-3-opus-20240229", "claude-3-haiku-20240307",
]


async def _anthropic_models(_session, _cfg, _api_key=None) -> list:
    return [{"name": m} for m in _ANTHROPIC_MODELS]


# --- Google Gemini ---
async def _google_generate(prompt: str, system: str, model: str, session, cfg: dict, api_key: str) -> str:
    path = cfg["generate_path"].replace("{model}", model)
    url = f"{cfg['base']}{path}?key={api_key}"
    payload = {
        "contents": [{"parts": [{"text": f"{system}\n\n{prompt}"}]}],
        "generationConfig": {"temperature": 0.2, "maxOutputTokens": 4096},
    }
    async with session.post(url, json=payload, timeout=180) as resp:
        if resp.status != 200:
            err = await resp.text()
            raise RuntimeError(f"Google HTTP {resp.status}: {err}")
        result = await resp.json()
        return result["candidates"][0]["content"]["parts"][0]["text"]


async def _google_models(session, cfg: dict, api_key: str) -> list:
    url = f"{cfg['base']}/models?key={api_key}"
    async with session.get(url, timeout=10) as resp:
        if resp.status != 200:
            return []
        data = await resp.json()
        return [{"name": m["name"]} for m in data.get("models", [])
                if "generateContent" in m.get("supportedGenerationMethods", [])]


# -------------------------------------------------------------------
# Provider router
# -------------------------------------------------------------------
PROVIDER_ROUTERS = {
    "ollama": (_ollama_generate, _ollama_models, False),
    "openai": (_openai_generate, _openai_models, True),
    "groq": (_openai_generate, _openai_models, True),
    "xai": (_openai_generate, _openai_models, True),
    "anthropic": (_anthropic_generate, _anthropic_models, True),
    "google": (_google_generate, _google_models, True),
}


async def generate(provider: str, prompt: str, system: str, model: str, session, api_key: str = "") -> str:
    gen_func, _, needs_key = PROVIDER_ROUTERS.get(provider, (None, None, False))
    if gen_func is None:
        raise ValueError(f"Unknown provider: {provider}")
    if needs_key and not api_key:
        raise ValueError(f"Provider '{provider}' requires an API key")
    cfg = PROVIDER_CONFIGS[provider]
    if provider in ("openai", "groq", "xai"):
        return await gen_func(prompt, system, model, session, cfg, api_key)
    elif provider == "anthropic":
        return await gen_func(prompt, system, model, session, cfg, api_key)
    elif provider == "google":
        return await gen_func(prompt, system, model, session, cfg, api_key)
    else:
        return await gen_func(prompt, system, model, session, cfg)


async def list_models(provider: str, session, api_key: str = "") -> list:
    _, models_func, needs_key = PROVIDER_ROUTERS.get(provider, (None, None, False))
    if models_func is None:
        return []
    cfg = PROVIDER_CONFIGS[provider]
    if needs_key:
        return await models_func(session, cfg, api_key)
    return await models_func(session, cfg)


# -------------------------------------------------------------------
# HTTP handlers
# -------------------------------------------------------------------

async def generate_build(request: web.Request) -> web.Response:
    try:
        data = await request.json()
    except Exception:
        return web.json_response({"blocks": [{"x": 0, "y": 1, "z": 0, "id": "minecraft:dirt"}]}, status=400)

    user_prompt = data.get("prompt", "a simple house")
    terrain_data = data.get("terrain", "[]")
    model = data.get("model", "") or DEFAULT_MODEL
    player_name = data.get("player_name", "Player")
    provider = data.get("provider", DEFAULT_PROVIDER)
    api_key = data.get("api_key", "")

    full_prompt = (
        f"Player name: {player_name}\n"
        f"Player position relative coords: (0,0,0)\n\n"
        f"LOCAL TERRAIN SCAN (Relative to player):\n{terrain_data}\n\n"
        f"USER REQUEST: {user_prompt}\n\n"
        f"Respond with JSON only:"
    )

    try:
        ai_response = await generate(provider, full_prompt, SYSTEM_PROMPT, model,
                                      request.app["http_session"], api_key)
        ai_response = _clean_response(ai_response)

        try:
            parsed = json.loads(ai_response)
            _fix_block_ids(parsed)
        except json.JSONDecodeError:
            logger.warning("AI returned invalid JSON, falling back")
            parsed = {"blocks": [{"x": 0, "y": 1, "z": 0, "id": "minecraft:dirt"}]}

        return web.json_response(parsed)

    except Exception as e:
        logger.exception("Generation failed for provider=%s model=%s", provider, model)
        return web.json_response(
            {"blocks": [{"x": 0, "y": 1, "z": 0, "id": "minecraft:dirt"}]},
        )


async def list_models_handler(request: web.Request) -> web.Response:
    provider = request.query.get("provider", DEFAULT_PROVIDER)
    api_key = request.query.get("api_key", "")
    try:
        models = await list_models(provider, request.app["http_session"], api_key)
        return web.json_response(models)
    except Exception as e:
        logger.warning("Failed to list models for %s: %s", provider, e)
        return web.json_response([])


async def health(_request: web.Request) -> web.Response:
    return web.json_response({"status": "ok", "version": "0.1.0", "providers": list(PROVIDER_CONFIGS.keys())})


async def on_startup(app: web.Application):
    import aiohttp
    app["http_session"] = aiohttp.ClientSession()


async def on_shutdown(app: web.Application):
    await app["http_session"].close()


def main():
    app = web.Application()
    app.on_startup.append(on_startup)
    app.on_shutdown.append(on_shutdown)
    app.router.add_post("/generate-build", generate_build)
    app.router.add_get("/list-models", list_models_handler)
    app.router.add_get("/health", health)

    logger.info("AI Bridge v0.1.0 — port %d | default provider=%s model=%s",
                BRIDGE_PORT, DEFAULT_PROVIDER, DEFAULT_MODEL)
    logger.info("Providers: %s", ", ".join(PROVIDER_CONFIGS.keys()))
    web.run_app(app, port=BRIDGE_PORT)


if __name__ == "__main__":
    main()
