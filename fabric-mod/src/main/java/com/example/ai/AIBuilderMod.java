package com.example.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

public class AIBuilderMod implements ModInitializer {
    private static final String BRIDGE_BASE = "http://localhost:5001";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("ai-builder-mod");

    private static ModConfig config = new ModConfig();

    private static final Stack<AIActionExecutor.BuildRecord> undoStack = new Stack<>();
    private static final Stack<AIActionExecutor.BuildRecord> redoStack = new Stack<>();
    private static final List<AIActionExecutor.BuildRecord> history = new ArrayList<>();
    private static final Map<String, AIActionExecutor.ConfirmAction> pendingActions = new HashMap<>();
    private static final Map<String, List<AIActionExecutor.ConfirmAction>> batchedConfirmations = new HashMap<>();

    public static ModConfig getConfig() { return config; }
    public static void saveConfig() { config.save(); }

    @Override
    public void onInitialize() {
        config = ModConfig.load();
        LOGGER.info("AI Builder Mod loaded (provider={}, model={})", config.selectedProvider, config.selectedModel);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            registerCommands(dispatcher)
        );
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        var aiCmd = CommandManager.literal("ai");

        aiCmd.then(CommandManager.literal("make")
                .then(CommandManager.argument("prompt", StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            String prompt = StringArgumentType.getString(context, "prompt");
                            player.sendMessage(Text.literal("§b[AI] Scanning terrain..."), false);
                            String terrain = TerrainScanner.scanArea(player.getEntityWorld(), player.getBlockPos(), 15);
                            player.sendMessage(Text.literal("§b[AI] " + player.getName().getString()
                                    + " asked: " + prompt), false);
                            sendToBridge(player, prompt, terrain);
                            return 1;
                        })));

        var chatCmd = CommandManager.literal("chat");
        chatCmd.then(CommandManager.literal("toggle")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    config.chatEnabled = !config.chatEnabled;
                    config.save();
                    player.sendMessage(Text.literal("§a[AI] Chat responses " + (config.chatEnabled ? "enabled" : "disabled")), false);
                    return 1;
                }));
        chatCmd.then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    String message = StringArgumentType.getString(context, "message");
                    sendChatToBridge(player, message);
                    return 1;
                }));
        aiCmd.then(chatCmd);

        aiCmd.then(CommandManager.literal("provider")
                .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            config.selectedProvider = StringArgumentType.getString(context, "name").toLowerCase();
                            config.save();
                            player.sendMessage(Text.literal("§a[AI] Provider set to: " + config.selectedProvider), false);
                            return 1;
                        })));

        aiCmd.then(CommandManager.literal("key")
                .then(CommandManager.argument("provider", StringArgumentType.word())
                        .then(CommandManager.argument("key", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    if (player == null) return 0;
                                    String prov = StringArgumentType.getString(context, "provider").toLowerCase();
                                    String key = StringArgumentType.getString(context, "key");
                                    config.apiKeys.put(prov, key);
                                    config.save();
                                    player.sendMessage(Text.literal("§a[AI] API key set for " + prov), false);
                                    return 1;
                                }))));

        aiCmd.then(CommandManager.literal("model")
                .then(CommandManager.argument("name", StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            config.selectedModel = StringArgumentType.getString(context, "name").trim();
                            config.save();
                            player.sendMessage(Text.literal("§a[AI] Model set to: " + config.selectedModel), false);
                            return 1;
                        })));

        aiCmd.then(CommandManager.literal("models")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    fetchModels(player);
                    return 1;
                }));

        aiCmd.then(CommandManager.literal("list")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    fetchModels(player);
                    return 1;
                }));

        aiCmd.then(CommandManager.literal("undo")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    undo(player);
                    return 1;
                }));

        aiCmd.then(CommandManager.literal("redo")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    redo(player);
                    return 1;
                }));

        aiCmd.then(CommandManager.literal("history")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    showHistory(player);
                    return 1;
                }));

        aiCmd.then(CommandManager.literal("confirm")
                .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            confirmAction(player, StringArgumentType.getString(context, "id"));
                            return 1;
                        })));

        aiCmd.then(CommandManager.literal("deny")
                .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            denyAction(player, StringArgumentType.getString(context, "id"));
                            return 1;
                        })));

        aiCmd.then(CommandManager.literal("gui")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    player.sendMessage(Text.literal("§e[AI] Use /ai provider <name>, /ai model <name>, and /ai key <provider> <key> to configure."), false);
                    return 1;
                }));

        aiCmd.then(CommandManager.literal("help")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    showHelp(player);
                    return 1;
                }));

        dispatcher.register(aiCmd);
    }

    // ---- Undo / Redo ----

    private void undo(ServerPlayerEntity player) {
        if (undoStack.isEmpty()) {
            player.sendMessage(Text.literal("§c[AI] Nothing to undo."), false);
            return;
        }
        AIActionExecutor.BuildRecord record = undoStack.pop();
        var changes = record.changes;
        for (int i = changes.size() - 1; i >= 0; i--) {
            var c = changes.get(i);
            player.getEntityWorld().setBlockState(c.pos, c.oldState, Block.NOTIFY_ALL);
        }
        redoStack.push(record);
        player.sendMessage(Text.literal("§e[AI] Undo: " + record.summary()), false);
    }

    private void redo(ServerPlayerEntity player) {
        if (redoStack.isEmpty()) {
            player.sendMessage(Text.literal("§c[AI] Nothing to redo."), false);
            return;
        }
        AIActionExecutor.BuildRecord record = redoStack.pop();
        for (var c : record.changes) {
            player.getEntityWorld().setBlockState(c.pos, c.newState, Block.NOTIFY_ALL);
        }
        undoStack.push(record);
        player.sendMessage(Text.literal("§e[AI] Redo: " + record.summary()), false);
    }

    // ---- History ----

    private void showHistory(ServerPlayerEntity player) {
        if (history.isEmpty()) {
            player.sendMessage(Text.literal("§c[AI] No build history."), false);
            return;
        }
        player.sendMessage(Text.literal("§b[AI] Build History:"), false);
        int idx = history.size();
        for (var r : history) {
            String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(r.timestamp));
            player.sendMessage(Text.literal(" §7#" + idx + " §f" + r.prompt
                    + " §7(" + time + ") §8" + r.summary()), false);
            idx--;
        }
    }

    // ---- Models ----

    private void fetchModels(ServerPlayerEntity player) {
        String key = config.apiKeys.getOrDefault(config.selectedProvider, "");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BRIDGE_BASE + "/list-models?provider=" + config.selectedProvider + "&api_key=" + key))
                .GET()
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(response -> player.getEntityWorld().getServer().execute(() -> {
                    try {
                        JsonArray models = GSON.fromJson(response, JsonArray.class);
                        player.sendMessage(Text.literal("§b[AI] Models (" + config.selectedProvider + "):"), false);
                        int count = 0;
                        for (var m : models) {
                            String name = m.getAsJsonObject().get("name").getAsString();
                            player.sendMessage(Text.literal(" §7- §f" + name), false);
                            if (++count >= 50) {
                                player.sendMessage(Text.literal(" §7... and " + (models.size() - 50) + " more"), false);
                                break;
                            }
                        }
                        if (!config.selectedModel.isEmpty())
                            player.sendMessage(Text.literal("§7Current: §f" + config.selectedModel), false);
                    } catch (Exception e) {
                        player.sendMessage(Text.literal("§c[AI] Failed to list models."), false);
                    }
                }))
                .exceptionally(ex -> {
                    player.sendMessage(Text.literal("§c[AI] Cannot reach bridge."), false);
                    return null;
                });
    }

    // ---- Confirm / Deny ----

    private void confirmAction(ServerPlayerEntity player, String id) {
        // Check batched confirmations first
        List<AIActionExecutor.ConfirmAction> batch = batchedConfirmations.remove(id);
        if (batch != null) {
            int count = 0;
            for (var action : batch) {
                if (!action.playerId.equals(player.getUuidAsString())) continue;
                switch (action.type) {
                    case "command" -> {
                        String cmd = action.command.startsWith("/") ? action.command.substring(1) : action.command;
                        player.getEntityWorld().getServer().getCommandManager().parseAndExecute(
                                player.getEntityWorld().getServer().getCommandSource(), cmd);
                        count++;
                    }
                    case "shell" -> {
                        AIActionExecutor.executeShell(action.command);
                        count++;
                    }
                }
            }
            player.sendMessage(Text.literal("§a[AI] Executed " + count + " actions"), false);
            return;
        }

        // Single action fallback
        AIActionExecutor.ConfirmAction action = pendingActions.remove(id);
        if (action == null) {
            player.sendMessage(Text.literal("§c[AI] No pending action: " + id), false);
            return;
        }
        if (!action.playerId.equals(player.getUuidAsString())) {
            player.sendMessage(Text.literal("§c[AI] This action belongs to another player."), false);
            pendingActions.put(id, action);
            return;
        }
        switch (action.type) {
            case "command" -> {
                String cmd = action.command.startsWith("/") ? action.command.substring(1) : action.command;
                player.getEntityWorld().getServer().getCommandManager().parseAndExecute(
                        player.getEntityWorld().getServer().getCommandSource(), cmd);
                player.sendMessage(Text.literal("§a[AI] Executed: " + action.command), false);
            }
            case "shell" -> player.getEntityWorld().getServer().execute(() -> {
                String output = AIActionExecutor.executeShell(action.command);
                player.sendMessage(Text.literal("§a[AI] Shell output:\n§7" + output), false);
            });
        }
    }

    private void denyAction(ServerPlayerEntity player, String id) {
        List<AIActionExecutor.ConfirmAction> batch = batchedConfirmations.remove(id);
        if (batch != null) {
            player.sendMessage(Text.literal("§e[AI] Denied " + batch.size() + " actions."), false);
            return;
        }
        AIActionExecutor.ConfirmAction action = pendingActions.remove(id);
        if (action == null) {
            player.sendMessage(Text.literal("§c[AI] No pending action: " + id), false);
            return;
        }
        player.sendMessage(Text.literal("§e[AI] Denied: " + action.description), false);
    }

    // ---- Help ----

    private void showHelp(ServerPlayerEntity player) {
        player.sendMessage(Text.literal("§b===== AI Builder Mod Commands ====="), false);
        player.sendMessage(Text.literal("§e/ai make <prompt> §7- Build or do anything"), false);
        player.sendMessage(Text.literal("§e/ai chat <message> §7- Chat with AI (has memory)"), false);
        player.sendMessage(Text.literal("§e/ai chat toggle §7- Toggle AI chat responses on/off"), false);
        player.sendMessage(Text.literal("§e/ai provider <name> §7- Switch provider"), false);
        player.sendMessage(Text.literal("§e/ai key <provider> <key> §7- Set API key"), false);
        player.sendMessage(Text.literal("§e/ai model <name> §7- Select AI model"), false);
        player.sendMessage(Text.literal("§e/ai models §7- List models for current provider"), false);
        player.sendMessage(Text.literal("§e/ai undo §7- Undo last AI build"), false);
        player.sendMessage(Text.literal("§e/ai redo §7- Redo last undone build"), false);
        player.sendMessage(Text.literal("§e/ai history §7- Show build history"), false);
        player.sendMessage(Text.literal("§e/ai confirm <id> §7- Confirm pending action"), false);
        player.sendMessage(Text.literal("§e/ai deny <id> §7- Deny pending action"), false);
        player.sendMessage(Text.literal("§7Provider: §f" + config.selectedProvider + " §7| Model: §f" + (config.selectedModel.isEmpty() ? "default" : config.selectedModel)), false);
        player.sendMessage(Text.literal("§7Chat responses: §" + (config.chatEnabled ? "aON" : "cOFF")), false);
    }

    // ---- Bridge call (make) ----

    private void sendToBridge(ServerPlayerEntity player, String prompt, String terrainData) {
        HttpClient client = HttpClient.newHttpClient();

        JsonObject payload = buildBasePayload(player, prompt, terrainData);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BRIDGE_BASE + "/generate-build"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(response -> {
                    if (response == null || response.isBlank()) return;
                    LOGGER.info("AI raw response:\n{}", response);
                    player.getEntityWorld().getServer().execute(() -> {
                        try {
                            processAiResponse(player, response, prompt, 0);
                        } catch (Exception e) {
                            player.sendMessage(Text.literal("§c[AI] Error: " + e.getMessage()), false);
                        }
                    });
                })
                .exceptionally(ex -> {
                    player.sendMessage(Text.literal("§c[AI] Cannot reach bridge."), false);
                    return null;
                });
    }

    // ---- Bridge call (chat) ----

    private void sendChatToBridge(ServerPlayerEntity player, String message) {
        HttpClient client = HttpClient.newHttpClient();

        JsonObject payload = new JsonObject();
        payload.addProperty("session_id", player.getUuidAsString());
        payload.addProperty("message", message);
        payload.addProperty("model", config.selectedModel);
        payload.addProperty("player_name", player.getName().getString());
        payload.addProperty("provider", config.selectedProvider);
        payload.addProperty("api_key", config.apiKeys.getOrDefault(config.selectedProvider, ""));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BRIDGE_BASE + "/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(response -> {
                    if (response == null || response.isBlank()) return;
                    LOGGER.info("AI chat raw response:\n{}", response);
                    player.getEntityWorld().getServer().execute(() -> {
                        try {
                            processAiResponse(player, response, message, 0);
                        } catch (Exception e) {
                            player.sendMessage(Text.literal("§c[AI] Error: " + e.getMessage()), false);
                        }
                    });
                })
                .exceptionally(ex -> {
                    player.sendMessage(Text.literal("§c[AI] Cannot reach bridge."), false);
                    return null;
                });
    }

    // ---- Build base payload ----

    private JsonObject buildBasePayload(ServerPlayerEntity player, String prompt, String terrainData) {
        JsonObject payload = new JsonObject();
        payload.addProperty("session_id", player.getUuidAsString());
        payload.addProperty("prompt", prompt);
        payload.addProperty("terrain", terrainData);
        payload.addProperty("player_x", player.getBlockX());
        payload.addProperty("player_y", player.getBlockY());
        payload.addProperty("player_z", player.getBlockZ());
        payload.addProperty("model", config.selectedModel);
        payload.addProperty("player_name", player.getName().getString());
        payload.addProperty("provider", config.selectedProvider);
        payload.addProperty("api_key", config.apiKeys.getOrDefault(config.selectedProvider, ""));
        return payload;
    }

    // ---- Process AI response (with optional follow-up loop) ----

    private void processAiResponse(ServerPlayerEntity player, String jsonResponse, String prompt, int depth) {
        var result = AIActionExecutor.execute(player.getEntityWorld(), player, jsonResponse);

        // Send AI chat message (only if player has chat responses enabled)
        if (result.chatMessage != null && !result.chatMessage.isEmpty() && config.chatEnabled) {
            player.sendMessage(Text.literal("§d[AI] " + result.chatMessage), false);
        }

        // Handle AI undo/redo requests
        if (result.undoRequested) {
            for (int i = 0; i < result.undoSteps && !undoStack.isEmpty(); i++) undo(player);
        }
        if (result.redoRequested) {
            for (int i = 0; i < result.redoSteps && !redoStack.isEmpty(); i++) redo(player);
        }

        // If there are captured command outputs, send follow-up request
        if (!result.capturedOutputs.isEmpty() && depth < 3) {
            sendCapturedOutputsFollowUp(player, result.capturedOutputs, depth, result);
            return;
        }

        // No more follow-ups — record build
        finishBuild(player, result, prompt);
    }

    // ---- Follow-up: send captured outputs to bridge ----

    private void sendCapturedOutputsFollowUp(ServerPlayerEntity player,
                                              List<CommandCapture.CapturedOutput> outputs,
                                              int depth,
                                              AIActionExecutor.ActionResult previousResult) {
        HttpClient client = HttpClient.newHttpClient();

        JsonObject payload = new JsonObject();
        payload.addProperty("session_id", player.getUuidAsString());
        payload.addProperty("model", config.selectedModel);
        payload.addProperty("player_name", player.getName().getString());
        payload.addProperty("provider", config.selectedProvider);
        payload.addProperty("api_key", config.apiKeys.getOrDefault(config.selectedProvider, ""));

        JsonArray outputsArr = new JsonArray();
        for (CommandCapture.CapturedOutput o : outputs) {
            JsonObject obj = new JsonObject();
            obj.addProperty("command", o.command);
            obj.addProperty("output", o.output);
            outputsArr.add(obj);
        }
        payload.add("command_outputs", outputsArr);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BRIDGE_BASE + "/command-result"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(response -> {
                    if (response == null || response.isBlank()) {
                        player.getEntityWorld().getServer().execute(() ->
                                finishBuild(player, previousResult, ""));
                        return;
                    }
                    LOGGER.info("AI follow-up raw response:\n{}", response);
                    player.getEntityWorld().getServer().execute(() -> {
                        processAiResponse(player, response, "", depth + 1);
                    });
                })
                .exceptionally(ex -> {
                    player.sendMessage(Text.literal("§c[AI] Follow-up error: " + ex.getMessage()), false);
                    player.getEntityWorld().getServer().execute(() ->
                            finishBuild(player, previousResult, ""));
                    return null;
                });
    }

    // ---- Finish build (record history, show summary) ----

    private void finishBuild(ServerPlayerEntity player, AIActionExecutor.ActionResult result, String prompt) {
        result.record.prompt = prompt;
        if (!result.record.changes.isEmpty() || result.record.commandsExecuted > 0
                || result.record.signsPlaced > 0) {
            undoStack.push(result.record);
            redoStack.clear();
            history.add(0, result.record);
            if (history.size() > 100) history.remove(history.size() - 1);
        }

        if (!result.record.changes.isEmpty()) {
            var summary = Text.literal("§a[AI] Done!");
            String recSummary = result.record.summary();
            if (!recSummary.isEmpty())
                summary.append(Text.literal(" §7(" + recSummary + ")"));
            player.sendMessage(summary, false);
        }

        if (!result.pendingConfirmations.isEmpty()) {
            var batchId = java.util.UUID.randomUUID().toString().substring(0, 8);
            var descBuilder = new StringBuilder("§e[AI] Confirm the following actions:\n");
            for (var ca : result.pendingConfirmations) {
                descBuilder.append(" §7• §f").append(ca.description).append("\n");
            }
            batchedConfirmations.put(batchId, new ArrayList<>(result.pendingConfirmations));
            var txt = Text.literal(descBuilder.toString())
                    .append(Text.literal("  §a[§lCONFIRM ALL§r§a]")
                            .styled(s -> s.withClickEvent(
                            new ClickEvent.RunCommand("/ai confirm " + batchId))
                                    .withHoverEvent(new HoverEvent.ShowText(
                                             Text.literal("Click to confirm all")))))
                    .append(Text.literal("  "))
                    .append(Text.literal("§c[§lDENY ALL§r§c]")
                            .styled(s -> s.withClickEvent(
                            new ClickEvent.RunCommand("/ai deny " + batchId))
                                    .withHoverEvent(new HoverEvent.ShowText(
                                             Text.literal("Click to deny all")))));
            player.sendMessage(txt, false);
        }
    }
}
