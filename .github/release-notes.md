## v0.1.0 — Initial Release

### Features
- `/ai make <prompt>` — AI-powered building with terrain awareness
- Multi-provider support: Ollama, OpenAI, Groq, xAI, Anthropic Claude, Google Gemini
- Live model listing for each provider
- API key entry via in-game chat
- Command execution (give, enchant, gamerule, teleport, etc.)
- Sign placement with AI-written text
- Chest filling with items
- Command block placement for minigames/automation
- Shell command execution (with confirmation)
- Chat-based CONFIRM/DENY for dangerous actions
- Undo / Redo system with build history
- AI-driven undo/redo
- Smart batching: AI splits large builds into parts
- Player-aware command targeting

### Installation
1. Download `ai-builder-mod-1.0.0.jar` and place in `.minecraft/mods/`
2. Install Python deps: `pip install -r requirements.txt`
3. Run bridge: `python3 bridge.py`
4. Launch Minecraft with Fabric 1.21.1
