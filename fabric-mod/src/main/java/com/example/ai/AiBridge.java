package com.example.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class AiBridge {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger("ai-bridge");

    private record ProviderConfig(String baseUrl, String chatPath, String modelsPath, String apiType) {}

    private static final Map<String, ProviderConfig> PROVIDERS = new HashMap<>();
    static {
        PROVIDERS.put("ollama", new ProviderConfig("http://localhost:11434", "/api/chat", "/api/tags", "ollama"));
        PROVIDERS.put("openai", new ProviderConfig("https://api.openai.com/v1", "/chat/completions", "/models", "openai"));
        PROVIDERS.put("groq", new ProviderConfig("https://api.groq.com/openai/v1", "/chat/completions", "/models", "openai"));
        PROVIDERS.put("xai", new ProviderConfig("https://api.x.ai/v1", "/chat/completions", "/models", "openai"));
        PROVIDERS.put("anthropic", new ProviderConfig("https://api.anthropic.com/v1", "/messages", "/models", "anthropic"));
        PROVIDERS.put("google", new ProviderConfig("https://generativelanguage.googleapis.com/v1beta", "/models/{model}:generateContent", "/models", "google"));
    }

    private static final Pattern SINGLE_LINE_COMMENT = Pattern.compile("//.*");
    private static final Pattern MULTI_LINE_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");

    private static final Map<String, String> BLOCK_CORRECTIONS = new HashMap<>();
    static {
        BLOCK_CORRECTIONS.put("minecraft:dirt_block", "minecraft:dirt");
        BLOCK_CORRECTIONS.put("minecraft:oak_plank", "minecraft:oak_planks");
        BLOCK_CORRECTIONS.put("minecraft:oak_wood", "minecraft:oak_log");
        BLOCK_CORRECTIONS.put("minecraft:stone_block", "minecraft:stone");
        BLOCK_CORRECTIONS.put("minecraft:grass", "minecraft:grass_block");
        BLOCK_CORRECTIONS.put("minecraft:planks", "minecraft:oak_planks");
        BLOCK_CORRECTIONS.put("minecraft:glass_pane", "minecraft:glass");
        BLOCK_CORRECTIONS.put("minecraft:cobble", "minecraft:cobblestone");
        BLOCK_CORRECTIONS.put("minecraft:wooden_planks", "minecraft:oak_planks");
        BLOCK_CORRECTIONS.put("minecraft:wooden_door", "minecraft:oak_door");
    }

    private static class SessionData {
        final List<JsonObject> messages = new ArrayList<>();
        long lastAccess = System.currentTimeMillis() / 1000;
    }

    private static final Map<String, SessionData> sessions = new ConcurrentHashMap<>();
    private static final long SESSION_TTL_SECONDS = 3600;

    private static final String SYSTEM_PROMPT =
        "You are a Minecraft AI assistant inside the game. You help players build structures, "
        + "run commands, change game modes, give items, and chat conversationally.\n\n"
        + "You receive terrain scan data and the player's request. Output a JSON object.\n\n"
        + "=== OUTPUT FORMAT ===\n"
        + "Your response must be a JSON object with these optional fields:\n\n"
        + "  \"chat\": \"text\" \u2014 A conversational message to send to the player in chat.\n"
        + "    Use this to respond naturally, ask questions, explain what you did.\n\n"
        + "  \"blocks\": [{\"x\": INT, \"y\": INT, \"z\": INT, \"id\": \"minecraft:block_id\"}]\n"
        + "    Place blocks. Coordinates are RELATIVE to the player.\n"
        + "    Common block IDs: oak_planks, stone, cobblestone, dirt, grass_block,\n"
        + "    oak_log, glass, oak_stairs, oak_slab, oak_door, oak_fence, torch,\n"
        + "    cobblestone_stairs, stone_bricks, brick_block, sandstone, white_wool, bookshelf\n\n"
        + "  \"commands\": [{\"cmd\": \"/command\", \"capture\": true|false, \"confirm\": true|false, \"desc\": \"...\"}]\n"
        + "    capture=true \u2014 execute and return the output to you (for /locate, /seed, etc.)\n"
        + "    capture=false (default) \u2014 run silently, no output returned\n"
        + "    confirm=true \u2014 ask player before executing\n"
        + "    confirm=false (default) \u2014 run immediately\n\n"
        + "  \"signs\": [{\"x\": INT, \"y\": INT, \"z\": INT, "
        + "\"lines\": [\"l1\",\"l2\",\"l3\",\"l4\"]}]\n\n"
        + "  \"chests\": [{\"x\": INT, \"y\": INT, \"z\": INT, "
        + "\"items\": [{\"id\": \"minecraft:item\", \"count\": N}]}]\n\n"
        + "  \"command_blocks\": [{\"x\": INT, \"y\": INT, \"z\": INT, "
        + "\"cmd\": \"/command\", \"mode\": \"IMPULSE|REPEAT|CHAIN\"}]\n\n"
        + "  \"undo\": {\"steps\": N}  \u2014 Undo previous builds\n"
        + "  \"redo\": {\"steps\": N}  \u2014 Redo undone builds\n\n"
        + "=== EXAMPLES ===\n\n"
        + "Player says: build a small house\n"
        + "{\n"
        + "  \"chat\": \"Building you a cozy oak house!\",\n"
        + "  \"blocks\": [\n"
        + "    {\"x\":0,\"y\":0,\"z\":0,\"id\":\"minecraft:oak_planks\"},\n"
        + "    {\"x\":1,\"y\":0,\"z\":0,\"id\":\"minecraft:oak_planks\"},\n"
        + "    {\"x\":2,\"y\":0,\"z\":0,\"id\":\"minecraft:oak_planks\"},\n"
        + "    {\"x\":0,\"y\":1,\"z\":0,\"id\":\"minecraft:oak_planks\"},\n"
        + "    {\"x\":2,\"y\":1,\"z\":0,\"id\":\"minecraft:glass\"},\n"
        + "    {\"x\":0,\"y\":2,\"z\":0,\"id\":\"minecraft:oak_planks\"},\n"
        + "    {\"x\":1,\"y\":2,\"z\":0,\"id\":\"minecraft:oak_planks\"},\n"
        + "    {\"x\":2,\"y\":2,\"z\":0,\"id\":\"minecraft:oak_planks\"}\n"
        + "  ]\n"
        + "}\n\n"
        + "Player says: give me a diamond sword\n"
        + "{\n"
        + "  \"chat\": \"Here you go, a sharp diamond sword!\",\n"
        + "  \"commands\": [{\"cmd\": \"/give @p minecraft:diamond_sword 1\", "
        + "\"desc\": \"Give diamond sword\", \"confirm\": true}]\n"
        + "}\n\n"
        + "Player says: set time to day\n"
        + "{\n"
        + "  \"chat\": \"Let there be light!\",\n"
        + "  \"commands\": [{\"cmd\": \"/time set day\", "
        + "\"desc\": \"Set time to day\", \"confirm\": false}]\n"
        + "}\n\n"
        + "Player says: give me a netherite kit\n"
        + "{\n"
        + "  \"chat\": \"Giving you the full netherite kit with enchanted trimmed armor!\",\n"
        + "  \"commands\": [\n"
        + "    {\"cmd\": \"/item replace entity @p armor.head with minecraft:netherite_helmet[enchantments={levels:{protection:4,unbreaking:3,mending:1}}]\", \"confirm\": true, \"desc\": \"Equip enchanted netherite helmet\"},\n"
        + "    {\"cmd\": \"/item replace entity @p armor.chest with minecraft:netherite_chestplate[enchantments={levels:{protection:4,unbreaking:3,mending:1}},trim={material:emerald,pattern:eye}]\", \"confirm\": true, \"desc\": \"Equip trimmed enchanted chestplate\"},\n"
        + "    {\"cmd\": \"/item replace entity @p armor.legs with minecraft:netherite_leggings[enchantments={levels:{protection:4,unbreaking:3,mending:1}}]\", \"confirm\": true, \"desc\": \"Equip enchanted netherite leggings\"},\n"
        + "    {\"cmd\": \"/item replace entity @p armor.feet with minecraft:netherite_boots[enchantments={levels:{protection:4,unbreaking:3,mending:1}}]\", \"confirm\": true, \"desc\": \"Equip enchanted netherite boots\"},\n"
        + "    {\"cmd\": \"/give @p minecraft:netherite_sword[enchantments={levels:{sharpness:5,unbreaking:3}}] 1\", \"confirm\": true, \"desc\": \"Give enchanted sword\"},\n"
        + "    {\"cmd\": \"/give @p minecraft:netherite_pickaxe[enchantments={levels:{efficiency:4,unbreaking:3}}] 1\", \"confirm\": true, \"desc\": \"Give enchanted pickaxe\"},\n"
        + "    {\"cmd\": \"/give @p minecraft:netherite_axe[enchantments={levels:{efficiency:4,unbreaking:3}}] 1\", \"confirm\": true, \"desc\": \"Give enchanted axe\"},\n"
        + "    {\"cmd\": \"/give @p minecraft:netherite_shovel[enchantments={levels:{efficiency:4,unbreaking:3}}] 1\", \"confirm\": true, \"desc\": \"Give enchanted shovel\"},\n"
        + "    {\"cmd\": \"/give @p minecraft:mace 1\", \"confirm\": true, \"desc\": \"Give mace\"},\n"
        + "    {\"cmd\": \"/item replace entity @p weapon.offhand with minecraft:wind_charge 64\", \"confirm\": true, \"desc\": \"Wind charges in off-hand\"},\n"
        + "    {\"cmd\": \"/give @p minecraft:enchanted_golden_apple 8\", \"confirm\": true, \"desc\": \"Give food\"}]\n"
        + "}\n\n"
        + "=== COORDINATES ===\n"
        + "- The player's position (player_x, player_y, player_z) is given in the request.\n"
        + "- All block coordinates in your response are RELATIVE to the player.\n"
        + "  - x=0, y=0, z=0 is the block the player is standing on (at their feet).\n"
        + "  - x=right, z=forward, y=up.\n"
        + "- Example: If player is at (100, 64, 200) and you want to place a block\n"
        + "  at world (105, 74, 200), use x=5, y=10, z=0.\n\n"
        + "=== BUILDING GUIDELINES ===\n"
        + "- For walls: place blocks in a rectangle, 3-4 blocks high\n"
        + "- For roofs: use slabs or stairs on top of walls\n"
        + "- For floors: fill the base area with planks or stone\n"
        + "- Add windows with glass blocks\n"
        + "- Add doors (oak_door needs 2 blocks tall, use TWO blocks at same x,z, y and y+1)\n"
        + "- Use wooden_doors by placing an oak_door block at y and y+1 with the correct facing\n"
        + "- For furniture: place a few blocks inside (furnace, chest, crafting_table)\n"
        + "- A simple 5x5x3 house needs about 40-50 blocks\n"
        + "- Use cobblestone for foundations, oak_planks for walls\n"
        + "- If terrain has water, build on stilts or use glass for underwater sections\n"
        + "- When building a structure on the ground, start at y=0 (ground level) going up.\n\n"
        + "=== WRITING TEXT WITH BLOCKS ===\n"
        + "- When the player asks you to write text, their name, or a message with blocks,\n"
        + "  use the 'blocks' field (NOT signs).\n"
        + "- Place blocks at y=10 to y=12 so the text appears at the player's eye level.\n"
        + "- Each letter should be about 5 blocks tall and 3-4 blocks wide.\n"
        + "- Use a contrasting color like black_concrete, red_wool, or white_wool.\n\n"
        + "=== COMMAND CAPTURE WORKFLOW ===\n"
        + "You can run Minecraft commands and SEE their output to make decisions:\n"
        + "  1. Use capture=true on commands like /locate, /seed, /time query, /getpos\n"
        + "  2. The command output will be shown back to you automatically\n"
        + "  3. You can then use that info in follow-up commands (e.g., teleport to found coordinates)\n\n"
        + "TELEPORT EXAMPLE:\n"
        + "When the player asks to teleport somewhere, ALWAYS use capture=true to get coordinates:\n\n"
        + "Player: \"teleport me to the nearest village\"\n"
        + "Your Step 1 response: {\"chat\": \"Locating nearest village...\", \"commands\": [{\"cmd\": \"/locate structure village\", \"capture\": true, \"confirm\": false, \"desc\": \"Find nearest village\"}]}\n"
        + "(NOTE: capture=true is REQUIRED so the command output comes back to you!)\n"
        + "  After that, you'll see the output like 'Village at [123, 64, 456]'\n"
        + "Your Step 2 response: {\"chat\": \"Found a village! Teleporting you now.\", \"commands\": [{\"cmd\": \"/tp @p 123 64 456\", \"confirm\": true, \"desc\": \"Teleport to nearest village\"}]}\n\n"
        + "=== RULES ===\n"
        + "- Output ONLY valid JSON. No markdown, no code fences.\n"
        + "- Always include a 'chat' field with a friendly message.\n"
        + "- Blocks, commands, signs etc. are OPTIONAL \u2014 only include what's needed.\n"
        + "- For simple chat (no blocks/commands), just send: {\"chat\": \"your message\"}\n"
        + "- Remember past conversations with the player.";

    private static final String FOLLOW_UP_SYSTEM_EXTRA =
        "\n\n=== COMMAND OUTPUT CAPTURE ===\n"
        + "The command output shown above is the result of a Minecraft command you requested.\n"
        + "Use this information to decide what to do next. For example:\n"
        + "- If you see coordinates, teleport the player there: {\"commands\": [{\"cmd\": \"/tp @p <x> <y> <z>\", \"confirm\": true}]}\n"
        + "- If you need more info, run another capture command\n"
        + "- If the task is done, just send a chat message\n\n"
        + "IMPORTANT: The output is real Minecraft command feedback. Parse it carefully.";

    // -------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------

    private static void cleanupSessions() {
        long now = System.currentTimeMillis() / 1000;
        sessions.entrySet().removeIf(e -> now - e.getValue().lastAccess > SESSION_TTL_SECONDS);
    }

    private static SessionData getSession(String sessionId) {
        cleanupSessions();
        SessionData sd = sessions.get(sessionId);
        if (sd == null) {
            sd = new SessionData();
            sessions.put(sessionId, sd);
        }
        sd.lastAccess = System.currentTimeMillis() / 1000;
        return sd;
    }

    private static void addMessage(String sessionId, String role, String content) {
        SessionData sd = getSession(sessionId);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        sd.messages.add(msg);
        if (sd.messages.size() > 50) {
            sd.messages.remove(0);
        }
    }

    // -------------------------------------------------------------------
    // Response cleaning
    // -------------------------------------------------------------------

    private static String stripJsonComments(String text) {
        text = SINGLE_LINE_COMMENT.matcher(text).replaceAll("");
        text = MULTI_LINE_COMMENT.matcher(text).replaceAll("");
        return text;
    }

    private static String cleanResponse(String text) {
        text = text.trim();
        if (text.startsWith("...") || text.startsWith("```")) {
            int idx = text.lastIndexOf("```");
            text = idx >= 0 ? text.substring(0, idx) : text;
        }
        if (text.contains("```")) {
            int start = text.indexOf("```json");
            if (start >= 0) {
                text = text.substring(start + 7);
            } else {
                start = text.indexOf("```");
                if (start >= 0) text = text.substring(start + 3);
            }
            int end = text.lastIndexOf("```");
            if (end >= 0) text = text.substring(0, end);
        }
        text = stripJsonComments(text);
        return text.trim();
    }

    private static JsonElement fixBlockIds(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (String key : List.of("id", "block")) {
                if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                    String val = obj.get(key).getAsString();
                    String bare = val.contains("[") ? val.substring(0, val.indexOf('[')) : val;
                    String corrected = BLOCK_CORRECTIONS.getOrDefault(bare, bare);
                    if (!corrected.equals(bare)) {
                        obj.addProperty(key, corrected);
                    }
                }
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                fixBlockIds(entry.getValue());
            }
            return obj;
        } else if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, fixBlockIds(arr.get(i)));
            }
            return arr;
        }
        return element;
    }

    // -------------------------------------------------------------------
    // Provider HTTP calls
    // -------------------------------------------------------------------

    private static CompletableFuture<String> httpPost(String url, String body, Map<String, String> headers, Duration timeout) {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
                    }
                    return resp.body();
                });
    }

    private static CompletableFuture<String> httpGet(String url, Map<String, String> headers, Duration timeout) {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .GET();
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) return "";
                    return resp.body();
                });
    }

    // --- Ollama ---

    private static CompletableFuture<String> callOllama(ProviderConfig cfg, String system, List<JsonObject> messages, String model) {
        JsonArray msgsArr = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", system);
        msgsArr.add(sysMsg);
        for (JsonObject m : messages) msgsArr.add(m);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.add("messages", msgsArr);
        payload.addProperty("stream", false);
        JsonObject opts = new JsonObject();
        opts.addProperty("temperature", 0.3);
        payload.add("options", opts);

        return httpPost(cfg.baseUrl() + cfg.chatPath(), GSON.toJson(payload), null, Duration.ofSeconds(300))
                .thenApply(body -> {
                    JsonObject resp = GSON.fromJson(body, JsonObject.class);
                    return resp.getAsJsonObject("message").get("content").getAsString();
                });
    }

    // --- OpenAI-compatible (OpenAI, Groq, xAI) ---

    private static CompletableFuture<String> callOpenAI(ProviderConfig cfg, String system, List<JsonObject> messages, String model, String apiKey) {
        JsonArray msgsArr = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", system);
        msgsArr.add(sysMsg);
        for (JsonObject m : messages) msgsArr.add(m);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.add("messages", msgsArr);
        payload.addProperty("temperature", 0.3);

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);

        return httpPost(cfg.baseUrl() + cfg.chatPath(), GSON.toJson(payload), headers, Duration.ofSeconds(300))
                .thenApply(body -> {
                    JsonObject resp = GSON.fromJson(body, JsonObject.class);
                    return resp.getAsJsonArray("choices").get(0).getAsJsonObject()
                            .getAsJsonObject("message").get("content").getAsString();
                });
    }

    // --- Anthropic ---

    private static CompletableFuture<String> callAnthropic(ProviderConfig cfg, String system, List<JsonObject> messages, String model, String apiKey) {
        JsonArray msgsArr = new JsonArray();
        for (JsonObject m : messages) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", m.get("role").getAsString());
            msg.addProperty("content", m.get("content").getAsString());
            msgsArr.add(msg);
        }

        if (msgsArr.isEmpty()) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("role", "user");
            fallback.addProperty("content", "...");
            msgsArr.add(fallback);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("max_tokens", 4096);
        payload.addProperty("system", system);
        payload.add("messages", msgsArr);
        payload.addProperty("temperature", 0.3);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", "2023-06-01");

        return httpPost(cfg.baseUrl() + cfg.chatPath(), GSON.toJson(payload), headers, Duration.ofSeconds(300))
                .thenApply(body -> {
                    JsonObject resp = GSON.fromJson(body, JsonObject.class);
                    return resp.getAsJsonArray("content").get(0).getAsJsonObject()
                            .get("text").getAsString();
                });
    }

    // --- Google Gemini ---

    private static CompletableFuture<String> callGoogle(ProviderConfig cfg, String system, List<JsonObject> messages, String model, String apiKey) {
        StringBuilder promptText = new StringBuilder();
        if (!messages.isEmpty()) {
            for (JsonObject m : messages) {
                if (promptText.length() > 0) promptText.append("\n");
                promptText.append(m.get("role").getAsString()).append(": ").append(m.get("content").getAsString());
            }
        } else {
            promptText.append("...");
        }

        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", system);
        JsonObject systemContents = new JsonObject();
        JsonArray systemParts = new JsonArray();
        systemParts.add(systemPart);
        systemContents.add("parts", systemParts);

        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", promptText.toString());
        JsonObject userContent = new JsonObject();
        JsonArray parts = new JsonArray();
        parts.add(userPart);
        userContent.add("parts", parts);

        JsonArray contents = new JsonArray();
        contents.add(userContent);

        JsonObject payload = new JsonObject();
        payload.add("contents", contents);
        payload.add("systemInstruction", systemContents);
        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("temperature", 0.3);
        genConfig.addProperty("maxOutputTokens", 4096);
        payload.add("generationConfig", genConfig);

        String path = cfg.chatPath().replace("{model}", model);
        String url = cfg.baseUrl() + path + "?key=" + apiKey;

        return httpPost(url, GSON.toJson(payload), null, Duration.ofSeconds(300))
                .thenApply(body -> {
                    JsonObject resp = GSON.fromJson(body, JsonObject.class);
                    return resp.getAsJsonArray("candidates").get(0).getAsJsonObject()
                            .getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject()
                            .get("text").getAsString();
                });
    }

    // -------------------------------------------------------------------
    // Provider router
    // -------------------------------------------------------------------

    private static CompletableFuture<String> callAi(String provider, String system, List<JsonObject> messages, String model, String apiKey) {
        ProviderConfig cfg = PROVIDERS.get(provider);
        if (cfg == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown provider: " + provider));
        }
        boolean needsKey = !"ollama".equals(cfg.apiType());
        if (needsKey && (apiKey == null || apiKey.isEmpty())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Provider '" + provider + "' requires an API key"));
        }
        return switch (cfg.apiType()) {
            case "ollama" -> callOllama(cfg, system, messages, model);
            case "openai" -> callOpenAI(cfg, system, messages, model, apiKey);
            case "anthropic" -> callAnthropic(cfg, system, messages, model, apiKey);
            case "google" -> callGoogle(cfg, system, messages, model, apiKey);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown API type: " + cfg.apiType()));
        };
    }

    // -------------------------------------------------------------------
    // Model listing
    // -------------------------------------------------------------------

    public static CompletableFuture<String> listModels(String provider, String apiKey) {
        ProviderConfig cfg = PROVIDERS.get(provider);
        if (cfg == null) {
            return CompletableFuture.completedFuture("[]");
        }
        return switch (cfg.apiType()) {
            case "ollama" -> listOllamaModels(cfg);
            case "openai" -> listOpenAIModels(cfg, apiKey);
            case "anthropic" -> CompletableFuture.completedFuture(
                    GSON.toJson(List.of(
                            Map.of("name", "claude-sonnet-4-20250514"),
                            Map.of("name", "claude-3-5-sonnet-20241022"),
                            Map.of("name", "claude-3-opus-20240229"),
                            Map.of("name", "claude-3-haiku-20240307"))));
            case "google" -> listGoogleModels(cfg, apiKey);
            default -> CompletableFuture.completedFuture("[]");
        };
    }

    private static CompletableFuture<String> listOllamaModels(ProviderConfig cfg) {
        return httpGet(cfg.baseUrl() + cfg.modelsPath(), null, Duration.ofSeconds(10))
                .thenApply(body -> {
                    if (body.isEmpty()) return "[]";
                    JsonObject resp = GSON.fromJson(body, JsonObject.class);
                    JsonArray models = resp.getAsJsonArray("models");
                    JsonArray result = new JsonArray();
                    if (models != null) {
                        for (JsonElement m : models) {
                            JsonObject obj = new JsonObject();
                            obj.addProperty("name", m.getAsJsonObject().get("name").getAsString());
                            result.add(obj);
                        }
                    }
                    return GSON.toJson(result);
                });
    }

    private static CompletableFuture<String> listOpenAIModels(ProviderConfig cfg, String apiKey) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        return httpGet(cfg.baseUrl() + cfg.modelsPath(), headers, Duration.ofSeconds(10))
                .thenApply(body -> {
                    if (body.isEmpty()) return "[]";
                    JsonObject resp = GSON.fromJson(body, JsonObject.class);
                    JsonArray data = resp.getAsJsonArray("data");
                    JsonArray result = new JsonArray();
                    if (data != null) {
                        for (JsonElement m : data) {
                            JsonObject obj = new JsonObject();
                            obj.addProperty("name", m.getAsJsonObject().get("id").getAsString());
                            result.add(obj);
                        }
                    }
                    return GSON.toJson(result);
                });
    }

    private static CompletableFuture<String> listGoogleModels(ProviderConfig cfg, String apiKey) {
        String url = cfg.baseUrl() + cfg.modelsPath() + "?key=" + apiKey;
        return httpGet(url, null, Duration.ofSeconds(10))
                .thenApply(body -> {
                    if (body.isEmpty()) return "[]";
                    JsonObject resp = GSON.fromJson(body, JsonObject.class);
                    JsonArray models = resp.getAsJsonArray("models");
                    JsonArray result = new JsonArray();
                    if (models != null) {
                        for (JsonElement m : models) {
                            JsonObject modelObj = m.getAsJsonObject();
                            if (modelObj.has("supportedGenerationMethods")) {
                                JsonArray methods = modelObj.getAsJsonArray("supportedGenerationMethods");
                                boolean supports = false;
                                for (JsonElement method : methods) {
                                    if ("generateContent".equals(method.getAsString())) {
                                        supports = true;
                                        break;
                                    }
                                }
                                if (supports) {
                                    JsonObject obj = new JsonObject();
                                    obj.addProperty("name", modelObj.get("name").getAsString());
                                    result.add(obj);
                                }
                            }
                        }
                    }
                    return GSON.toJson(result);
                });
    }

    // -------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------

    public static CompletableFuture<String> generateBuild(String sessionId, String prompt, String terrain,
                                                           String model, String playerName, String provider,
                                                           String apiKey, int playerX, int playerY, int playerZ,
                                                           boolean mini) {
        String fullPrompt;
        if (mini) {
            if (sessions.containsKey(sessionId)) {
                sessions.remove(sessionId);
            }
            fullPrompt = "Player: " + playerName + " at (" + playerX + ", " + playerY + ", " + playerZ + ")\n"
                    + "Request: " + prompt + "\n\n"
                    + "Note: No terrain scan provided to save context tokens. "
                    + "Respond ONLY with chat and/or commands (no blocks).\n\n"
                    + "Respond with JSON:";
        } else {
            fullPrompt = "Player: " + playerName + " at (" + playerX + ", " + playerY + ", " + playerZ + ")\n"
                    + "Request: " + prompt + "\n\n"
                    + "Terrain scan (blocks around player, relative coords):\n" + terrain + "\n\n"
                    + "Respond with JSON:";
        }

        addMessage(sessionId, "user", fullPrompt);
        List<JsonObject> convMessages = new ArrayList<>(getSession(sessionId).messages);

        return callAi(provider, SYSTEM_PROMPT, convMessages, model, apiKey)
                .thenApply(response -> {
                    String cleaned = cleanResponse(response);
                    LOGGER.info("Raw AI response:\n{}", cleaned);
                    try {
                        JsonElement parsed = GSON.fromJson(cleaned, JsonElement.class);
                        parsed = fixBlockIds(parsed);
                        String aiContent = GSON.toJson(parsed);
                        addMessage(sessionId, "assistant", aiContent);
                        return aiContent;
                    } catch (Exception e) {
                        LOGGER.warn("AI returned invalid JSON, wrapping in chat");
                        String fallback = "{\"chat\":\"I received your request but had trouble processing it. Raw response: "
                                + (cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned) + "\"}";
                        addMessage(sessionId, "assistant", fallback);
                        return fallback;
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.error("Generation failed for provider={} model={}", provider, model, ex);
                    String err = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
                    if (err.contains("context length") || err.contains("context_length")
                            || err.contains("too many tokens") || err.contains("maximum context")
                            || err.contains("prompt is too long")) {
                        return "{\"chat\":\"Context limit reached! Use §e/ai mini <prompt>§r to send without terrain data (saves tokens). Error: "
                                + ex.getMessage() + "\"}";
                    }
                    return "{\"chat\":\"Sorry, I encountered an error: " + ex.getMessage() + "\"}";
                });
    }

    public static CompletableFuture<String> chat(String sessionId, String message,
                                                   String model, String playerName, String provider,
                                                   String apiKey, int playerX, int playerY, int playerZ) {
        if (message == null || message.isBlank()) {
            return CompletableFuture.completedFuture("{\"chat\":\"Say something!\"}");
        }

        String fullMessage = "Player: " + playerName + " at (" + playerX + ", " + playerY + ", " + playerZ + ")\nRequest: " + message;
        addMessage(sessionId, "user", fullMessage);
        List<JsonObject> convMessages = new ArrayList<>(getSession(sessionId).messages);

        return callAi(provider, SYSTEM_PROMPT, convMessages, model, apiKey)
                .thenApply(response -> {
                    String cleaned = cleanResponse(response);
                    try {
                        JsonElement parsed = GSON.fromJson(cleaned, JsonElement.class);
                        String aiContent = GSON.toJson(parsed);
                        addMessage(sessionId, "assistant", aiContent);
                        return aiContent;
                    } catch (Exception e) {
                        String fallback = "{\"chat\":\"" + (cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned) + "\"}";
                        addMessage(sessionId, "assistant", fallback);
                        return fallback;
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.error("Chat failed for provider={} model={}", provider, model, ex);
                    String err = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
                    if (err.contains("context length") || err.contains("context_length")
                            || err.contains("too many tokens") || err.contains("maximum context")
                            || err.contains("prompt is too long")) {
                        return "{\"chat\":\"Context limit reached! Try §e/ai mini <prompt>§r to start fresh without terrain data. Error: "
                                + ex.getMessage() + "\"}";
                    }
                    return "{\"chat\":\"Error: " + ex.getMessage() + "\"}";
                });
    }

    public static CompletableFuture<String> commandResult(String sessionId, String model,
                                                           String playerName, String provider,
                                                           String apiKey, int playerX, int playerY, int playerZ,
                                                           List<CommandCapture.CapturedOutput> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return CompletableFuture.completedFuture("{\"chat\":\"No command outputs to process.\"}");
        }

        StringBuilder context = new StringBuilder();
        for (CommandCapture.CapturedOutput o : outputs) {
            if (context.length() > 0) context.append("\n\n");
            context.append("Command executed: ").append(o.command).append("\nOutput:\n").append(o.output);
        }

        String followUpPrompt = "Player: " + playerName + " at (" + playerX + ", " + playerY + ", " + playerZ + ")\n\n"
                + "The following commands were executed and their output was captured:\n\n"
                + context + "\n\n"
                + "Based on this output, take the necessary follow-up actions "
                + "(teleport the player, build something, run more commands, etc.). "
                + "If the output contains coordinates, use them.\n\n"
                + "Respond with JSON:";

        addMessage(sessionId, "user", followUpPrompt);
        List<JsonObject> convMessages = new ArrayList<>(getSession(sessionId).messages);

        String followUpSystem = SYSTEM_PROMPT + FOLLOW_UP_SYSTEM_EXTRA;

        return callAi(provider, followUpSystem, convMessages, model, apiKey)
                .thenApply(response -> {
                    String cleaned = cleanResponse(response);
                    LOGGER.info("AI follow-up raw response:\n{}", cleaned);
                    try {
                        JsonElement parsed = GSON.fromJson(cleaned, JsonElement.class);
                        parsed = fixBlockIds(parsed);
                        String aiContent = GSON.toJson(parsed);
                        addMessage(sessionId, "assistant", aiContent);
                        return aiContent;
                    } catch (Exception e) {
                        LOGGER.warn("AI follow-up returned invalid JSON, wrapping in chat");
                        String fallback = "{\"chat\":\"Command result processed. Raw: "
                                + (cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned) + "\"}";
                        addMessage(sessionId, "assistant", fallback);
                        return fallback;
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.error("Follow-up generation failed for provider={} model={}", provider, model, ex);
                    String err = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
                    if (err.contains("context length") || err.contains("context_length")
                            || err.contains("too many tokens") || err.contains("maximum context")
                            || err.contains("prompt is too long")) {
                        return "{\"chat\":\"Context limit reached! Try starting fresh with §e/ai mini <prompt>§r. Error: "
                                + ex.getMessage() + "\"}";
                    }
                    return "{\"chat\":\"Follow-up error: " + ex.getMessage() + "\"}";
                });
    }
}
