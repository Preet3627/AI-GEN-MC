# 🧠 AI Builder Mod — Minecraft × Ollama × Multi-Provider AI

[![Version](https://img.shields.io/badge/version-v0.1.0--beta-blue)]()
[![License](https://img.shields.io/badge/license-Apache%202.0-green)](LICENSE)
[![Fabric](https://img.shields.io/badge/mod%20loader-Fabric-dbd0b4)]()

> **Build anything in Minecraft using AI** — houses, bridges, roller coasters, PVP arenas, minigames. The AI reads your terrain, adapts to water/mountains, places blocks, runs commands, fills chests, writes signs, places command blocks, and more.

---

## ✨ Features

| Feature | Description |
|---|---|
| 🏗️ **AI Building** | `/ai make <prompt>` — build anything with terrain awareness |
| 🌊 **Terrain Adaptive** | Scans native block IDs around you; adapts to water, hills, caves |
| 🎮 **Command Execution** | AI runs `/give`, `/enchant`, `/gamerule`, `/tp`, `/gamemode`, `/time` etc. |
| 📋 **Signs & Chests** | Places signs with text, fills chests with items |
| 🔄 **Command Blocks** | Places impulse/repeat/chain command blocks for minigames |
| 🖥️ **Shell Access** | AI can execute system commands (requires confirmation) |
| ✅ **Confirmation System** | Destructive actions show clickable **CONFIRM** / **DENY** in chat |
| 🔙 **Undo / Redo** | `/ai undo` / `/ai redo` — revert or reapply AI builds |
| 📜 **Build History** | `/ai history` — see all past AI-generated structures |
| 🤖 **AI Undo/Redo** | AI can undo/redo its own builds automatically |
| 🔌 **Multi-Provider** | Supports **Ollama**, **OpenAI**, **Groq**, **xAI**, **Anthropic Claude**, **Google Gemini** |
| 🔑 **Live API Keys** | `/ai key <provider> <key>` — enter keys in chat |
| 📡 **Model Listing** | `/ai models` — fetch available models from your provider |
| 🧩 **Smart Batching** | AI splits large builds (e.g. 12 houses) into parts, copy-pastes, fills terrain |
| 👤 **Player Aware** | Knows your gamertag, targets commands correctly |

---

## 🛠️ Requirements

- **Minecraft**: 1.21.1 with **Fabric Loader** 0.16+
- **Java**: 21+
- **Python**: 3.9+
- **Ollama** (optional, for local models): [ollama.com](https://ollama.com)
- **API Keys** (for cloud providers): OpenAI / Anthropic / Google / Groq / xAI

---

## 📦 Setup

### 1️⃣ Python Bridge

```bash
cd python-bridge
pip install -r requirements.txt
python3 bridge.py
```

The bridge runs on `http://localhost:5001`.

### 2️⃣ Compile the Mod

```bash
cd fabric-mod
./gradlew build
```

Copy `fabric-mod/build/libs/ai-builder-mod-1.0.0.jar` → `.minecraft/mods/`

### 3️⃣ Launch Minecraft

Use the Fabric profile. Join any world.

---

## 🎮 Commands

| Command | Description |
|---|---|
| `/ai make <prompt>` | Ask AI to build or do anything |
| `/ai provider <name>` | Switch AI provider: `ollama`, `openai`, `groq`, `xai`, `anthropic`, `google` |
| `/ai key <provider> <key>` | Set API key for a provider (session-only, not stored) |
| `/ai model <name>` | Select a specific model |
| `/ai models` | List available models from current provider |
| `/ai list` | Alias for `/ai models` |
| `/ai undo` | Undo the last AI build |
| `/ai redo` | Redo the last undone AI build |
| `/ai history` | Show build history |
| `/ai help` | Show all commands |
| `/ai confirm <id>` | Confirm a pending action |
| `/ai deny <id>` | Deny a pending action |

---

## 🧠 Provider Setup

### Ollama (Local)

```bash
ollama pull gpt-oss:120b-cloud
# or: ollama pull deepseek-r1:1.5b
# or: ollama pull llama3.2
```
Default provider. No API key needed. Set model with `/ai model <name>`.

### OpenAI

```
/ai provider openai
/ai key openai sk-your-key-here
/ai models           # fetches available models
/ai model gpt-4o
```

### Groq

```
/ai provider groq
/ai key groq gsk-your-key-here
/ai models
/ai model llama-3.3-70b-versatile
```

### xAI

```
/ai provider xai
/ai key xai xai-your-key-here
/ai models
/ai model grok-2
```

### Anthropic Claude

```
/ai provider anthropic
/ai key anthropic sk-ant-your-key-here
/ai model claude-sonnet-4-20250514
```

### Google Gemini

```
/ai provider google
/ai key google AIza-your-key-here
/ai model gemini-2.5-flash
```

---

## 🏗️ Example Builds

```text
/ai make a cozy cottage with a fireplace
/ai make a bridge across this river
/ai make a fishing hut on stilts over water
/ai make a PVP arena with command block death tracking
/ai make a storage room with chests full of tools and food
/ai make a welcome sign at the entrance
/ai make a roller coaster station with powered rails
/ai make a 10-story tower with a beacon on top
/ai make 12 houses in a village layout
/ai make a nether portal room with obsidian frame
/ai enable keep inventory and give me 5 diamonds
/ai teleport me to x=100 z=200
```

---

## 🤖 How It Works

```
┌──────────────────┐     POST /generate-build     ┌──────────────────┐     Provider API     ┌──────────┐
│  Minecraft Mod    │ ──────────────────────────> │  Python Bridge   │ ──────────────────> │  OpenAI   │
│  (Fabric 1.21.1)  │ <────────────────────────── │  (aiohttp :5001) │ <────────────────── │  Groq     │
│  TerrainScanner   │     JSON action object      │  bridge.py       │                    │  xAI      │
│  AIActionExecutor │                             │  Multi-Provider  │                    │  Claude   │
│  BuildHistory     │                             │  Router          │                    │  Gemini   │
└──────────────────┘                             └──────────────────┘                    │  Ollama   │
                                                                                        └──────────┘
```

1. Player types `/ai make a fishing hut`
2. **TerrainScanner** samples a 31×31×9 area around the player → native block IDs
3. Mod sends terrain + prompt + provider + model + player name → Python bridge
4. Bridge routes to the selected AI provider with a Minecraft-aware system prompt
5. AI returns a JSON object: `{"blocks": [...], "commands": [...], "signs": [...], "chests": [...], "command_blocks": [...]}`
6. **AIActionExecutor** processes each action type — places blocks, runs commands, fills chests, etc.
7. **BuildHistory** records every change for undo/redo

---

## 📂 Project Structure

```
├── fabric-mod/
│   ├── build.gradle / settings.gradle / gradle.properties
│   └── src/main/java/com/example/ai/
│       ├── AIBuilderMod.java         # Commands + HTTP + provider/key mgmt
│       ├── AIActionExecutor.java     # Action handler + build history
│       └── TerrainScanner.java       # Chunk scanner
├── python-bridge/
│   ├── requirements.txt
│   └── bridge.py                     # Multi-provider AI router
├── tests/
│   ├── pvp-arena-prompt.txt          # Test prompts
│   └── pvp-arena-response.json       # Expected AI output
├── .github/
│   └── workflows/
│       └── release.yml               # Auto-release workflow
├── LICENSE                           # Apache 2.0
├── .gitignore
└── README.md
```

---

## 🧪 Tests

Test prompts and expected AI responses are in [`tests/`](tests/).

To run a test:
```bash
cat tests/pvp-arena-prompt.txt | curl -X POST http://localhost:5001/generate-build \
  -H "Content-Type: application/json" -d @-
```

---

## 🔐 Security

- **API keys** are stored in memory only — lost on disconnect
- **Shell commands** always require player confirmation
- **Destructive Minecraft commands** (`/kill`, `/op`, `/ban`) require confirmation
- The mod runs on the **server thread** — no client exploits

---

## 📜 License

Apache 2.0 — see [LICENSE](LICENSE).

---

## 👤 Author

**Created & Developed by [Latestinssan](https://github.com/Preet3627/)**

[![GitHub](https://img.shields.io/badge/GitHub-@Preet3627-181717?logo=github)](https://github.com/Preet3627/)
