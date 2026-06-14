## AI Builder Mod v1.0.0 — Minecraft 1.21.11

This release ports the mod to **Minecraft 1.21.11 Fabric** and adds a custom mod icon.

### What's Changed
- **Minecraft 1.21.11** — updated all deps: Fabric Loom 1.14.10, Fabric API 0.141.4, Yarn mappings 1.21.11+build.6
- **Fixed API breaks** — adapted to 1.21.11 API changes (`getEntityWorld`, `parseAndExecute`, `ClickEvent`/`HoverEvent` interface refactor)
- **Mod icon** — added AI Builder icon
- **Version range** — mod accepts `>=1.21.1` so it loads on 1.21.11 without issues

### Features
- `/ai make <prompt>` — AI-powered terrain-aware building
- Multi-provider AI support: Ollama, OpenAI, Groq, xAI, Anthropic, Google
- Undo/redo, build history, confirmation system for commands/shell
- Python bridge (`python-bridge/bridge.py`) with live model listing
- Block placement, signs, chests, command blocks, shell commands
- AI-initiated undo/redo

### Assets
- `ai-builder-mod-*.jar` — the Fabric mod
- `bridge.py` — Python AI bridge (run alongside the mod)
- `requirements.txt` — Python dependencies
