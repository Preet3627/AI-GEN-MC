## v0.2.0 — No Backend Required

The Python bridge is gone. The mod now talks to AI providers directly — zero setup, zero external processes.

### What's New

- **Self-contained** — No Python, no bridge server, no `pip install`. Just the JAR.
- **`AiBridge.java`** — Full multi-provider AI router built into the mod. Supports Ollama, OpenAI, Groq, xAI, Anthropic Claude, and Google Gemini.
- **Session memory** — Conversation history is managed in-mod per player.
- **Response cleaning** — Markdown fences, JSON comments, and block ID corrections all handled internally.

### Breaking Changes

- The `python-bridge/` directory is no longer needed and can be deleted.
- No more `http://localhost:5001` — the mod calls provider APIs directly.
- No more starting `bridge.py` before launching Minecraft.

### Project Structure

```
fabric-mod/src/main/java/com/example/ai/
├── AIBuilderMod.java         # Commands, undo/redo, confirm/deny
├── AiBridge.java             # NEW — Multi-provider AI router
├── AIActionExecutor.java     # Action handler + build history
├── TerrainScanner.java       # Chunk scanner
├── CommandCapture.java       # Command output capture
└── ModConfig.java            # Config persistence
```

### Full Changelog

- Ported `python-bridge/bridge.py` (694 lines) into `AiBridge.java`
- Removed all HTTP client code from `AIBuilderMod.java` — no more `HttpClient`, `HttpRequest`, `HttpResponse`
- Session management with 1-hour TTL and 50-message cap built into the mod
- Response cleaning: strips markdown fences, JSON comments, corrects block IDs (dirt_block → dirt, etc.)
- Provider-specific API implementations for all 6 providers with correct auth and payload formats
- Follow-up loop for captured command results handled in-mod
- Release workflow no longer builds/packages Python bridge
