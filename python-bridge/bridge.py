AI Bridge for Minecraft Builder Mod — Multi-Provider (v0.2.0)

Features:
  - Multi-provider AI (Ollama, OpenAI, Groq, xAI, Anthropic, Google)
  - Per-player chat sessions with conversation memory
  - Terrain-aware building
  - Chat responses, commands, shell execution

import json
import logging
import os
import time
import uuid
from typing import Optional

from aiohttp import web

from templates import TEMPLATES

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ai-bridge")

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

sessions: dict = {}  # session_id -> list of {"role": ..., "content": ...}
SESSION_TTL = 3600  # 1 hour

def _cleanup_sessions():
    now = time.time()
    stale = [sid for sid, s in sessions.items() if now - s.get("_ts", 0) > SESSION_TTL]
    for sid in stale:
        del sessions[sid]

def _get_session(session_id: str) -> list:
    _cleanup_sessions()
    if session_id not in sessions:
        sessions[session_id] = {"messages": [], "_ts": time.time()}
    sessions[session_id]["_ts"] = time.time()
    return sessions[session_id]["messages"]

def _add_message(session_id: str, role: str, content: str):
    msgs = _get_session(session_id)
    msgs.append({"role": role, "content": content})
    if len(msgs) > 50:
        msgs.pop(0)

_TEMPLATE_LIST = "\n".join(
    f'  - "{name}": {t["description"]} ({t["dimensions"]}, ~{t["block_count"]})'
    for name, t in TEMPLATES.items()
)

SYSTEM_PROMPT = (
    "You are a Minecraft AI assistant inside the game. You help players build structures, "
    "run commands, change game modes, give items, and chat conversationally.\n\n"
    "You receive terrain scan data and the player's request. Output a JSON object.\n\n"
    "=== OUTPUT FORMAT ===\n"
    "Your response must be a JSON object with these optional fields:\n\n"
    '  "chat": "text" — A conversational message to send to the player in chat.\n'
    '    Use this to respond naturally, ask questions, explain what you did.\n\n'
    '  "blocks": [{"x": INT, "y": INT, "z": INT, "id": "minecraft:block_id"}]\n'
    "    Place blocks. Coordinates are RELATIVE to the player.\n"
    "    Common block IDs: oak_planks, stone, cobblestone, dirt, grass_block,\n"
    "    oak_log, glass, oak_stairs, oak_slab, oak_door, oak_fence, torch,\n"
    "    cobblestone_stairs, stone_bricks, brick_block, sandstone, white_wool, bookshelf\n\n"
     '  "commands": [{"cmd": "/command", "capture": true|false, "confirm": true|false, "desc": "..."}]\n'
    "    capture=true — execute and return the output to you (for /locate, /seed, etc.)\n"
    "    capture=false (default) — run silently, no output returned\n"
    "    confirm=true — ask player before executing\n"
    "    confirm=false (default) — run immediately\n\n"
    '  "signs": [{"x": INT, "y": INT, "z": INT, '
    '"lines": ["l1","l2","l3","l4"]}]\n\n'
    '  "chests": [{"x": INT, "y": INT, "z": INT, '
    '"items": [{"id": "minecraft:item", "count": N}]}]\n\n'
    '  "command_blocks": [{"x": INT, "y": INT, "z": INT, '
    '"cmd": "/command", "mode": "IMPULSE|REPEAT|CHAIN"}]\n\n'
    '  "undo": {"steps": N}  — Undo previous builds\n'
    '  "redo": {"steps": N}  — Redo undone builds\n\n'
    "=== EXAMPLES ===\n\n"
    "Player says: build a small house\n"
    '{\n'
    '  "chat": "Building you a cozy oak house!",\n'
    '  "blocks": [\n'
    '    {"x":0,"y":0,"z":0,"id":"minecraft:oak_planks"},\n'
    '    {"x":1,"y":0,"z":0,"id":"minecraft:oak_planks"},\n'
    '    {"x":2,"y":0,"z":0,"id":"minecraft:oak_planks"},\n'
    '    {"x":0,"y":1,"z":0,"id":"minecraft:oak_planks"},\n'
    '    {"x":2,"y":1,"z":0,"id":"minecraft:glass"},\n'
    '    {"x":0,"y":2,"z":0,"id":"minecraft:oak_planks"},\n'
    '    {"x":1,"y":2,"z":0,"id":"minecraft:oak_planks"},\n'
    '    {"x":2,"y":2,"z":0,"id":"minecraft:oak_planks"}\n'
    "  ]\n"
    "}\n\n"
    "Player says: give me a diamond sword\n"
    '{\n'
    '  "chat": "Here you go, a sharp diamond sword!",\n'
    '  "commands": [{"cmd": "/give @p minecraft:diamond_sword 1", '
    '"desc": "Give diamond sword", "confirm": true}]\n'
    "}\n\n"
    "Player says: set time to day\n"
    '{\n'
    '  "chat": "Let there be light!",\n'
    '  "commands": [{"cmd": "/time set day", '
    '"desc": "Set time to day", "confirm": false}]\n'
    "}\n\n"
    "Player says: give me a netherite kit\n"
    '{\n'
    '  "chat": "Giving you the full netherite kit with enchanted trimmed armor!",\n'
    '  "commands": [\n'
    '    {"cmd": "/item replace entity @p armor.head with minecraft:netherite_helmet[enchantments={levels:{protection:4,unbreaking:3,mending:1}}]", "confirm": true, "desc": "Equip enchanted netherite helmet"},\n'
    '    {"cmd": "/item replace entity @p armor.chest with minecraft:netherite_chestplate[enchantments={levels:{protection:4,unbreaking:3,mending:1}},trim={material:emerald,pattern:eye}]", "confirm": true, "desc": "Equip trimmed enchanted chestplate"},\n'
    '    {"cmd": "/item replace entity @p armor.legs with minecraft:netherite_leggings[enchantments={levels:{protection:4,unbreaking:3,mending:1}}]", "confirm": true, "desc": "Equip enchanted netherite leggings"},\n'
    '    {"cmd": "/item replace entity @p armor.feet with minecraft:netherite_boots[enchantments={levels:{protection:4,unbreaking:3,mending:1}}]", "confirm": true, "desc": "Equip enchanted netherite boots"},\n'
    '    {"cmd": "/give @p minecraft:netherite_sword[enchantments={levels:{sharpness:5,unbreaking:3}}] 1", "confirm": true, "desc": "Give enchanted sword"},\n'
    '    {"cmd": "/give @p minecraft:netherite_pickaxe[enchantments={levels:{efficiency:4,unbreaking:3}}] 1", "confirm": true, "desc": "Give enchanted pickaxe"},\n'
    '    {"cmd": "/give @p minecraft:netherite_axe[enchantments={levels:{efficiency:4,unbreaking:3}}] 1", "confirm": true, "desc": "Give enchanted axe"},\n'
    '    {"cmd": "/give @p minecraft:netherite_shovel[enchantments={levels:{efficiency:4,unbreaking:3}}] 1", "confirm": true, "desc": "Give enchanted shovel"},\n'
    '    {"cmd": "/give @p minecraft:mace 1", "confirm": true, "desc": "Give mace"},\n'
    '    {"cmd": "/item replace entity @p weapon.offhand with minecraft:wind_charge 64", "confirm": true, "desc": "Wind charges in off-hand"},\n'
    '    {"cmd": "/give @p minecraft:enchanted_golden_apple 8", "confirm": true, "desc": "Give food"}]\n'
    "}\n\n"
    f"=== AVAILABLE BUILD TEMPLATES ===\n"
    f"You have pre-defined build templates available. When the player asks for a specific "
    f"structure, check if it matches one of these templates by name. If so, use the template "
    f"instructions to build it block-by-block.\n\n"
    f"Available templates:\n{_TEMPLATE_LIST}\n\n"
    f"If the player asks for a structure that matches a template, check its instructions "
    f"and build it.\n\n"
    "=== BUILDING GUIDELINES ===\n"
    "- For walls: place blocks in a rectangle, 3-4 blocks high\n"
    "- For roofs: use slabs or stairs on top of walls\n"
    "- For floors: fill the base area with planks or stone\n"
    "- Add windows with glass blocks\n"
    "- Add doors (oak_door needs 2 blocks tall, use TWO blocks at same x,z, y and y+1)\n"
    "- Use wooden_doors by placing an oak_door block at y and y+1 with the correct facing\n"
    "- For furniture: place a few blocks inside (furnace, chest, crafting_table)\n"
    "- A simple 5x5x3 house needs about 40-50 blocks\n"
    "- Use cobblestone for foundations, oak_planks for walls\n"
    "- If terrain has water, build on stilts or use glass for underwater sections\n\n"
    "=== COMMAND CAPTURE WORKFLOW ===\n"
    "You can run Minecraft commands and SEE their output to make decisions:\n"
    "  1. Use capture=true on commands like /locate, /seed, /time query, /getpos\n"
    "  2. The command output will be shown back to you automatically\n"
    "  3. You can then use that info in follow-up commands (e.g., teleport to found coordinates)\n\n"
    "Example: Player asks 'teleport me to a village'\n"
    "  Step 1 (your response):\n"
    '    {"chat": "Searching for a village...", "commands": [{"cmd": "/locate structure village", "capture": true, "desc": "Find nearest village"}]}\n'
    "  Step 2 (after seeing output like 'Village at [123, 64, 456]'):\n"
    '    {"chat": "Found a village! Teleporting you now.", "commands": [{"cmd": "/tp @p 123 64 456", "confirm": true, "desc": "Teleport to village"}]}\n\n'
    "=== RULES ===\n"
    "- Output ONLY valid JSON. No markdown, no code fences.\n"
    "- Always include a 'chat' field with a friendly message.\n"
    "- Blocks, commands, signs etc. are OPTIONAL — only include what's needed.\n"
    "- For simple chat (no blocks/commands), just send: {\"chat\": \"your message\"}\n"
    "- Remember past conversations with the player.\n"
    "- Coordinates are RELATIVE to the player. x=right, z=forward, y=up."
)

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
    "minecraft:wooden_door": "minecraft:oak_door",
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



async def _ollama_generate(prompt: str, system: str, model: str, session, cfg: dict,
                           api_key: str = "", messages: list = None) -> str:
    url = f"{cfg['base']}{cfg['generate_path']}"
    if messages:
        payload = {
            "model": model,
            "messages": [{"role": "system", "content": system}] + messages,
            "stream": False,
            "options": {"temperature": 0.3},
        }
    else:
        payload = {
            "model": model,
            "prompt": prompt,
            "system": system,
            "stream": False,
            "options": {"temperature": 0.3},
        }
    async with session.post(url, json=payload, timeout=300) as resp:
        if resp.status != 200:
            raise RuntimeError(f"Ollama HTTP {resp.status}")
        result = await resp.json()
        if messages:
            return result["message"]["content"]
        return result.get("response", "")

async def _ollama_models(session, cfg: dict, api_key: str = "") -> list:
    url = f"{cfg['base']}{cfg['models_path']}"
    async with session.get(url, timeout=10) as resp:
        if resp.status != 200:
            return []
        data = await resp.json()
        return [{"name": m["name"]} for m in data.get("models", [])]


async def _openai_generate(prompt: str, system: str, model: str, session, cfg: dict,
                           api_key: str = "", messages: list = None) -> str:
    url = f"{cfg['base']}{cfg['generate_path']}"
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    msgs = [{"role": "system", "content": system}]
    if messages:
        msgs += messages
    else:
        msgs.append({"role": "user", "content": prompt})
    payload = {"model": model, "messages": msgs, "temperature": 0.3}
    async with session.post(url, json=payload, headers=headers, timeout=300) as resp:
        if resp.status != 200:
            err = await resp.text()
            raise RuntimeError(f"OpenAI HTTP {resp.status}: {err}")
        result = await resp.json()
        return result["choices"][0]["message"]["content"]

async def _openai_models(session, cfg: dict, api_key: str = "") -> list:
    url = f"{cfg['base']}{cfg['models_path']}"
    headers = {"Authorization": f"Bearer {api_key}"}
    async with session.get(url, headers=headers, timeout=10) as resp:
        if resp.status != 200:
            return []
        data = await resp.json()
        return [{"name": m["id"]} for m in data.get("data", [])]


async def _anthropic_generate(prompt: str, system: str, model: str, session, cfg: dict,
                              api_key: str = "", messages: list = None) -> str:
    url = f"{cfg['base']}{cfg['generate_path']}"
    headers = {
        "x-api-key": api_key,
        "anthropic-version": "2023-06-01",
        "Content-Type": "application/json",
    }
    msgs = messages if messages else [{"role": "user", "content": prompt}]
    payload = {
        "model": model,
        "max_tokens": 4096,
        "system": system,
        "messages": msgs,
        "temperature": 0.3,
    }
    async with session.post(url, json=payload, headers=headers, timeout=300) as resp:
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


async def _google_generate(prompt: str, system: str, model: str, session, cfg: dict,
                           api_key: str = "", messages: list = None) -> str:
    path = cfg["generate_path"].replace("{model}", model)
    url = f"{cfg['base']}{path}?key={api_key}"
    prompt_text = prompt
    if messages:
        prompt_text = "\n".join(f"{m['role']}: {m['content']}" for m in messages)
    payload = {
        "contents": [{"parts": [{"text": f"{system}\n\n{prompt_text}"}]}],
        "generationConfig": {"temperature": 0.3, "maxOutputTokens": 4096},
    }
    async with session.post(url, json=payload, timeout=300) as resp:
        if resp.status != 200:
            err = await resp.text()
            raise RuntimeError(f"Google HTTP {resp.status}: {err}")
        result = await resp.json()
        return result["candidates"][0]["content"]["parts"][0]["text"]

async def _google_models(session, cfg: dict, api_key: str = "") -> list:
    url = f"{cfg['base']}/models?key={api_key}"
    async with session.get(url, timeout=10) as resp:
        if resp.status != 200:
            return []
        data = await resp.json()
        return [{"name": m["name"]} for m in data.get("models", [])
                if "generateContent" in m.get("supportedGenerationMethods", [])]


PROVIDER_ROUTERS = {
    "ollama": (_ollama_generate, _ollama_models, False),
    "openai": (_openai_generate, _openai_models, True),
    "groq": (_openai_generate, _openai_models, True),
    "xai": (_openai_generate, _openai_models, True),
    "anthropic": (_anthropic_generate, _anthropic_models, True),
    "google": (_google_generate, _google_models, True),
}


async def generate(provider: str, prompt: str, system: str, model: str,
                   session, api_key: str = "", messages: list = None) -> str:
    gen_func, _, needs_key = PROVIDER_ROUTERS.get(provider, (None, None, False))
    if gen_func is None:
        raise ValueError(f"Unknown provider: {provider}")
    if needs_key and not api_key:
        raise ValueError(f"Provider '{provider}' requires an API key")
    cfg = PROVIDER_CONFIGS[provider]
    return await gen_func(prompt, system, model, session, cfg, api_key, messages)


async def list_models(provider: str, session, api_key: str = "") -> list:
    _, models_func, needs_key = PROVIDER_ROUTERS.get(provider, (None, None, False))
    if models_func is None:
        return []
    cfg = PROVIDER_CONFIGS[provider]
    return await models_func(session, cfg, api_key)



async def generate_build(request: web.Request) -> web.Response:
    try
        data = await request.json()
    except Exception:
        return web.json_response({"chat": "Error reading request."}, status=400)

    session_id = data.get("session_id", "default")
    user_prompt = data.get("prompt", "a simple house")
    terrain_data = data.get("terrain", "[]")
    model = data.get("model", "") or DEFAULT_MODEL
    player_name = data.get("player_name", "Player")
    provider = data.get("provider", DEFAULT_PROVIDER)
    api_key = data.get("api_key", "")

    full_prompt = (
        f"Player: {player_name}\n"
        f"Request: {user_prompt}\n\n"
        f"Terrain scan (blocks around player, relative coords):\n{terrain_data}\n\n"
        f"Respond with JSON:"
    )

    conv = _get_session(session_id)
    _add_message(session_id, "user", full_prompt)

    try:
        ai_response = await generate(provider, full_prompt, SYSTEM_PROMPT, model,
                                      request.app["http_session"], api_key, conv)
        ai_response = _clean_response(ai_response)
        logger.info("Raw AI response:\n%s", ai_response)

        try:
            parsed = json.loads(ai_response)
            _fix_block_ids(parsed)
        except json.JSONDecodeError:
            logger.warning("AI returned invalid JSON, wrapping in chat")
            parsed = {"chat": f"I received your request but had trouble processing it. Raw response: {ai_response[:200]}"}

        ai_content = json.dumps(parsed)
        _add_message(session_id, "assistant", ai_content)

        return web.json_response(parsed)

    except Exception as e:
        logger.exception("Generation failed for provider=%s model=%s", provider, model)
        return web.json_response({"chat": f"Sorry, I encountered an error: {str(e)}"})


async def chat_handler(request: web.Request) -> web.Response:
    try:
        data = await request.json()
    except Exception:
        return web.json_response({"error": "invalid json"}, status=400)

    session_id = data.get("session_id", "default")
    message = data.get("message", "")
    model = data.get("model", "") or DEFAULT_MODEL
    provider = data.get("provider", DEFAULT_PROVIDER)
    api_key = data.get("api_key", "")
    player_name = data.get("player_name", "Player")

    if not message:
        return web.json_response({"chat": "Say something!"})

    conv = _get_session(session_id)
    _add_message(session_id, "user", message)

    chat_system = (
        "You are a friendly Minecraft AI assistant. You chat with the player, answer questions, "
        "and can help with the game. Be concise, helpful, and stay in character.\n\n"
        "You can also perform actions by outputting JSON with these fields:\n"
        '- "chat": your response text (always include this)\n'
        '- "commands": [{"cmd": "...", "confirm": true}]\n'
        '- "blocks": [{"x":0,"y":1,"z":0,"id":"minecraft:..."}]\n\n'
        "When the player asks you to do something in-game (build, give items, change game mode), "
        "use the JSON action fields alongside your chat response.\n\n"
        "Output ONLY valid JSON."
    )

    try:
        ai_response = await generate(provider, message, chat_system, model,
                                      request.app["http_session"], api_key, conv)
        ai_response = _clean_response(ai_response)

        try:
            parsed = json.loads(ai_response)
        except json.JSONDecodeError:
            parsed = {"chat": ai_response[:500]}

        _add_message(session_id, "assistant", json.dumps(parsed))
        return web.json_response(parsed)

    except Exception as e:
        logger.exception("Chat failed")
        return web.json_response({"chat": f"Error: {str(e)}"})


async def command_result_handler(request: web.Request) -> web.Response:
    try:
        data = await request.json()
    except Exception:
        return web.json_response({"chat": "Error reading request."}, status=400)

    session_id = data.get("session_id", "default")
    model = data.get("model", "") or DEFAULT_MODEL
    provider = data.get("provider", DEFAULT_PROVIDER)
    api_key = data.get("api_key", "")
    player_name = data.get("player_name", "Player")
    outputs = data.get("command_outputs", [])

    if not outputs:
        return web.json_response({"chat": "No command outputs to process."})

    lines = [f"Command executed: {o.get('command', '?')}\nOutput:\n{o.get('output', '(empty)')}" for o in outputs]
    context = "\n\n".join(lines)
    follow_up_prompt = (
        f"Player: {player_name}\n\n"
        f"The following commands were executed and their output was captured:\n\n"
        f"{context}\n\n"
        f"Based on this output, take the necessary follow-up actions "
        f"(teleport the player, build something, run more commands, etc.). "
        f"If the output contains coordinates, use them.\n\n"
        f"Respond with JSON:"
    )

    conv = _get_session(session_id)
    _add_message(session_id, "user", follow_up_prompt)

    follow_up_system = SYSTEM_PROMPT + (
        "\n\n=== COMMAND OUTPUT CAPTURE ===\n"
        "The command output shown above is the result of a Minecraft command you requested.\n"
        "Use this information to decide what to do next. For example:\n"
        '- If you see coordinates, teleport the player there: {"commands": [{"cmd": "/tp @p <x> <y> <z>", "confirm": true}]}\n'
        '- If you need more info, run another capture command\n'
        '- If the task is done, just send a chat message\n\n'
        "IMPORTANT: The output is real Minecraft command feedback. Parse it carefully."
    )

    try:
        ai_response = await generate(provider, follow_up_prompt, follow_up_system, model,
                                      request.app["http_session"], api_key, conv)
        ai_response = _clean_response(ai_response)
        logger.info("AI follow-up raw response:\n%s", ai_response)

        try:
            parsed = json.loads(ai_response)
            _fix_block_ids(parsed)
        except json.JSONDecodeError:
            logger.warning("AI follow-up returned invalid JSON, wrapping in chat")
            parsed = {"chat": f"Command result processed. Raw: {ai_response[:200]}"}

        _add_message(session_id, "assistant", json.dumps(parsed))
        return web.json_response(parsed)

    except Exception as e:
        logger.exception("Follow-up generation failed")
        return web.json_response({"chat": f"Follow-up error: {str(e)}"})


async def list_templates_handler(_request: web.Request) -> web.Response:
    return web.json_response(TEMPLATES)


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
    return web.json_response({
        "status": "ok",
        "version": "0.2.0",
        "providers": list(PROVIDER_CONFIGS.keys()),
        "sessions": len(sessions),
    })


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
    app.router.add_post("/chat", chat_handler)
    app.router.add_post("/command-result", command_result_handler)
    app.router.add_get("/list-models", list_models_handler)
    app.router.add_get("/list-templates", list_templates_handler)
    app.router.add_get("/health", health)

    logger.info("AI Bridge v0.2.0 — port %d | default provider=%s model=%s",
                BRIDGE_PORT, DEFAULT_PROVIDER, DEFAULT_MODEL)
    logger.info("Providers: %s", ", ".join(PROVIDER_CONFIGS.keys()))
    web.run_app(app, port=BRIDGE_PORT)


if __name__ == "__main__":
    main()
