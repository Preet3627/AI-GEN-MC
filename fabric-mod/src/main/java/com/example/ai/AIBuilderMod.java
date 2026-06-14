package com.example.ai;

import com.google.gson.Gson;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

public class AIBuilderMod implements ModInitializer {
    private static final String BRIDGE_BASE = "http://localhost:5001";
    private static final Gson GSON = new Gson();

    private static String selectedProvider = "ollama";
    private static String selectedModel = "";
    private static final Map<String, String> apiKeys = new HashMap<>();

    private static final Stack<AIActionExecutor.BuildRecord> undoStack = new Stack<>();
    private static final Stack<AIActionExecutor.BuildRecord> redoStack = new Stack<>();
    private static final List<AIActionExecutor.BuildRecord> history = new ArrayList<>();
    private static final Map<String, AIActionExecutor.ConfirmAction> pendingActions = new HashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            registerCommands(dispatcher)
        );
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        var aiCmd = CommandManager.literal("ai");

        // /ai make <prompt>
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

        // /ai provider <name>
        aiCmd.then(CommandManager.literal("provider")
                .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            selectedProvider = StringArgumentType.getString(context, "name").toLowerCase();
                            player.sendMessage(Text.literal("§a[AI] Provider set to: " + selectedProvider), false);
                            return 1;
                        })));

        // /ai key <provider> <key>
        aiCmd.then(CommandManager.literal("key")
                .then(CommandManager.argument("provider", StringArgumentType.word())
                        .then(CommandManager.argument("key", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    if (player == null) return 0;
                                    String prov = StringArgumentType.getString(context, "provider").toLowerCase();
                                    String key = StringArgumentType.getString(context, "key");
                                    apiKeys.put(prov, key);
                                    player.sendMessage(Text.literal("§a[AI] API key set for " + prov), false);
                                    return 1;
                                }))));

        // /ai model <name>
        aiCmd.then(CommandManager.literal("model")
                .then(CommandManager.argument("name", StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            selectedModel = StringArgumentType.getString(context, "name").trim();
                            player.sendMessage(Text.literal("§a[AI] Model set to: " + selectedModel), false);
                            return 1;
                        })));

        // /ai models
        aiCmd.then(CommandManager.literal("models")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    fetchModels(player);
                    return 1;
                }));

        // /ai list (alias for models)
        aiCmd.then(CommandManager.literal("list")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    fetchModels(player);
                    return 1;
                }));

        // /ai undo
        aiCmd.then(CommandManager.literal("undo")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    undo(player);
                    return 1;
                }));

        // /ai redo
        aiCmd.then(CommandManager.literal("redo")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    redo(player);
                    return 1;
                }));

        // /ai history
        aiCmd.then(CommandManager.literal("history")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    showHistory(player);
                    return 1;
                }));

        // /ai confirm <id>
        aiCmd.then(CommandManager.literal("confirm")
                .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            confirmAction(player, StringArgumentType.getString(context, "id"));
                            return 1;
                        })));

        // /ai deny <id>
        aiCmd.then(CommandManager.literal("deny")
                .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            denyAction(player, StringArgumentType.getString(context, "id"));
                            return 1;
                        })));

        // /ai help
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
        String key = apiKeys.getOrDefault(selectedProvider, "");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BRIDGE_BASE + "/list-models?provider=" + selectedProvider + "&api_key=" + key))
                .GET()
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(response -> player.getEntityWorld().getServer().execute(() -> {
                    try {
                        JsonArray models = GSON.fromJson(response, JsonArray.class);
                        player.sendMessage(Text.literal("§b[AI] Models (" + selectedProvider + "):"), false);
                        int count = 0;
                        for (var m : models) {
                            String name = m.getAsJsonObject().get("name").getAsString();
                            player.sendMessage(Text.literal(" §7- §f" + name), false);
                            if (++count >= 50) {
                                player.sendMessage(Text.literal(" §7... and " + (models.size() - 50) + " more"), false);
                                break;
                            }
                        }
                        if (!selectedModel.isEmpty())
                            player.sendMessage(Text.literal("§7Current: §f" + selectedModel), false);
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
        player.sendMessage(Text.literal("§e/ai provider <name> §7- Switch provider (ollama/openai/groq/xai/anthropic/google)"), false);
        player.sendMessage(Text.literal("§e/ai key <provider> <key> §7- Set API key"), false);
        player.sendMessage(Text.literal("§e/ai model <name> §7- Select AI model"), false);
        player.sendMessage(Text.literal("§e/ai models §7- List models for current provider"), false);
        player.sendMessage(Text.literal("§e/ai undo §7- Undo last AI build"), false);
        player.sendMessage(Text.literal("§e/ai redo §7- Redo last undone build"), false);
        player.sendMessage(Text.literal("§e/ai history §7- Show build history"), false);
        player.sendMessage(Text.literal("§e/ai confirm <id> §7- Confirm pending action"), false);
        player.sendMessage(Text.literal("§e/ai deny <id> §7- Deny pending action"), false);
    }

    // ---- Bridge call ----

    private void sendToBridge(ServerPlayerEntity player, String prompt, String terrainData) {
        HttpClient client = HttpClient.newHttpClient();

        JsonObject payload = new JsonObject();
        payload.addProperty("prompt", prompt);
        payload.addProperty("terrain", terrainData);
        payload.addProperty("player_x", player.getBlockX());
        payload.addProperty("player_y", player.getBlockY());
        payload.addProperty("player_z", player.getBlockZ());
        payload.addProperty("model", selectedModel);
        payload.addProperty("player_name", player.getName().getString());
        payload.addProperty("provider", selectedProvider);
        payload.addProperty("api_key", apiKeys.getOrDefault(selectedProvider, ""));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BRIDGE_BASE + "/generate-build"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(response -> {
                    if (response == null || response.isBlank()) return;
                    player.getEntityWorld().getServer().execute(() -> {
                        try {
                            var result = AIActionExecutor.execute(player.getEntityWorld(), player, response);

                            // Handle AI undo/redo requests
                            if (result.undoRequested) {
                                for (int i = 0; i < result.undoSteps && !undoStack.isEmpty(); i++) undo(player);
                            }
                            if (result.redoRequested) {
                                for (int i = 0; i < result.redoSteps && !redoStack.isEmpty(); i++) redo(player);
                            }

                            // Record build history
                            result.record.prompt = prompt;
                            if (!result.record.changes.isEmpty() || result.record.commandsExecuted > 0
                                    || result.record.signsPlaced > 0) {
                                undoStack.push(result.record);
                                redoStack.clear();
                                history.add(0, result.record);
                                if (history.size() > 100) history.remove(history.size() - 1);
                            }

                            // Summary message
                            var summary = Text.literal("§a[AI] Done!");
                            String recSummary = result.record.summary();
                            if (!recSummary.isEmpty())
                                summary.append(Text.literal(" §7(" + recSummary + ")"));
                            player.sendMessage(summary, false);

                            // Pending confirmations
                            for (var ca : result.pendingConfirmations) {
                                pendingActions.put(ca.id, ca);
                                var txt = Text.literal("§e[AI] " + ca.description + "\n")
                                        .append(Text.literal("  §a[§lCONFIRM§r§a]")
                                                .styled(s -> s.withClickEvent(
                                                new ClickEvent.RunCommand("/ai confirm " + ca.id))
                                                        .withHoverEvent(new HoverEvent.ShowText(
                                                                 Text.literal("Click to confirm")))))
                                        .append(Text.literal("  "))
                                        .append(Text.literal("§c[§lDENY§r§c]")
                                                .styled(s -> s.withClickEvent(
                                                new ClickEvent.RunCommand("/ai deny " + ca.id))
                                                        .withHoverEvent(new HoverEvent.ShowText(
                                                                 Text.literal("Click to deny")))));
                                player.sendMessage(txt, false);
                            }
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
}
