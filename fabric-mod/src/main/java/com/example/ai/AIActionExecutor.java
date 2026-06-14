package com.example.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AIActionExecutor {
    private static final Gson GSON = new Gson();

    public static class BlockChange {
        public BlockPos pos;
        public BlockState oldState;
        public BlockState newState;

        public BlockChange(BlockPos pos, BlockState oldState, BlockState newState) {
            this.pos = pos; this.oldState = oldState; this.newState = newState;
        }
    }

    public static class BuildRecord {
        public long timestamp = System.currentTimeMillis();
        public String prompt;
        public List<BlockChange> changes = new ArrayList<>();
        public int commandsExecuted;
        public int signsPlaced;
        public int chestsFilled;
        public int commandBlocksPlaced;

        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (!changes.isEmpty()) sb.append(changes.size()).append(" blocks");
            if (commandsExecuted > 0) sb.append(sb.length() > 0 ? ", " : "").append(commandsExecuted).append(" commands");
            if (signsPlaced > 0) sb.append(sb.length() > 0 ? ", " : "").append(signsPlaced).append(" signs");
            if (chestsFilled > 0) sb.append(sb.length() > 0 ? ", " : "").append(chestsFilled).append(" chests");
            if (commandBlocksPlaced > 0) sb.append(sb.length() > 0 ? ", " : "").append(commandBlocksPlaced).append(" cmd_blocks");
            return sb.toString();
        }
    }

    public static class ConfirmAction {
        public String id;
        public String command;
        public String description;
        public String type;
        public String playerId;
    }

    public static class ActionResult {
        public BuildRecord record = new BuildRecord();
        public int blocksPlaced;
        public List<ConfirmAction> pendingConfirmations = new ArrayList<>();
        public boolean undoRequested;
        public int undoSteps;
        public boolean redoRequested;
        public int redoSteps;
    }

    public static ActionResult execute(ServerWorld world, ServerPlayerEntity player, String jsonResponse) {
        JsonElement root;
        try {
            root = GSON.fromJson(jsonResponse, JsonElement.class);
        } catch (Exception e) {
            throw new RuntimeException("Invalid JSON from AI bridge", e);
        }

        ActionResult result = new ActionResult();

        if (root.isJsonArray()) {
            recordBlockChanges(world, root.getAsJsonArray(), player, result);
            return result;
        }

        if (!root.isJsonObject()) return result;
        JsonObject obj = root.getAsJsonObject();

        if (obj.has("undo")) {
            result.undoRequested = true;
            result.undoSteps = obj.get("undo").getAsJsonObject().has("steps")
                    ? obj.get("undo").getAsJsonObject().get("steps").getAsInt() : 1;
        }
        if (obj.has("redo")) {
            result.redoRequested = true;
            result.redoSteps = obj.get("redo").getAsJsonObject().has("steps")
                    ? obj.get("redo").getAsJsonObject().get("steps").getAsInt() : 1;
        }

        if (obj.has("blocks"))
            recordBlockChanges(world, obj.getAsJsonArray("blocks"), player, result);

        if (obj.has("commands")) {
            for (JsonElement e : obj.getAsJsonArray("commands")) {
                JsonObject cmdObj = e.getAsJsonObject();
                String cmd = cmdObj.get("cmd").getAsString();
                String desc = cmdObj.has("desc") ? cmdObj.get("desc").getAsString() : cmd;
                boolean confirm = cmdObj.has("confirm") && cmdObj.get("confirm").getAsBoolean();
                if (confirm) {
                    ConfirmAction ca = new ConfirmAction();
                    ca.id = java.util.UUID.randomUUID().toString().substring(0, 8);
                    ca.command = cmd;
                    ca.description = desc;
                    ca.type = "command";
                    ca.playerId = player.getUuidAsString();
                    result.pendingConfirmations.add(ca);
                } else {
                    executeCommand(world, cmd);
                    result.record.commandsExecuted++;
                }
            }
        }

        if (obj.has("signs")) {
            for (JsonElement e : obj.getAsJsonArray("signs")) {
                JsonObject s = e.getAsJsonObject();
                int x = s.get("x").getAsInt() + player.getBlockX();
                int y = s.get("y").getAsInt() + player.getBlockY();
                int z = s.get("z").getAsInt() + player.getBlockZ();
                String[] lines = new String[4];
                if (s.has("lines")) {
                    JsonArray arr = s.getAsJsonArray("lines");
                    for (int i = 0; i < 4 && i < arr.size(); i++)
                        lines[i] = arr.get(i).getAsString();
                }
                if (placeSign(world, x, y, z, lines)) result.record.signsPlaced++;
            }
        }

        if (obj.has("chests")) {
            for (JsonElement e : obj.getAsJsonArray("chests")) {
                JsonObject c = e.getAsJsonObject();
                int x = c.get("x").getAsInt() + player.getBlockX();
                int y = c.get("y").getAsInt() + player.getBlockY();
                int z = c.get("z").getAsInt() + player.getBlockZ();
                JsonArray items = c.getAsJsonArray("items");
                if (fillChest(world, x, y, z, items)) result.record.chestsFilled++;
            }
        }

        if (obj.has("command_blocks")) {
            for (JsonElement e : obj.getAsJsonArray("command_blocks")) {
                JsonObject cb = e.getAsJsonObject();
                int x = cb.get("x").getAsInt() + player.getBlockX();
                int y = cb.get("y").getAsInt() + player.getBlockY();
                int z = cb.get("z").getAsInt() + player.getBlockZ();
                String cmd = cb.get("cmd").getAsString();
                String mode = cb.has("mode") ? cb.get("mode").getAsString() : "IMPULSE";
                boolean conditional = cb.has("conditional") && cb.get("conditional").getAsBoolean();
                if (placeCommandBlock(world, x, y, z, cmd, mode, conditional)) result.record.commandBlocksPlaced++;
            }
        }

        if (obj.has("shell")) {
            for (JsonElement e : obj.getAsJsonArray("shell")) {
                JsonObject sh = e.getAsJsonObject();
                String cmd = sh.get("cmd").getAsString();
                boolean confirm = !sh.has("confirm") || sh.get("confirm").getAsBoolean();
                if (confirm) {
                    ConfirmAction ca = new ConfirmAction();
                    ca.id = java.util.UUID.randomUUID().toString().substring(0, 8);
                    ca.command = cmd;
                    ca.description = "Shell: " + cmd;
                    ca.type = "shell";
                    ca.playerId = player.getUuidAsString();
                    result.pendingConfirmations.add(ca);
                } else {
                    executeShell(cmd);
                }
            }
        }

        return result;
    }

    private static void recordBlockChanges(ServerWorld world, JsonArray blocks, ServerPlayerEntity player, ActionResult result) {
        int ox = player.getBlockX(), oy = player.getBlockY(), oz = player.getBlockZ();
        for (JsonElement element : blocks) {
            JsonObject obj = element.getAsJsonObject();
            int x = obj.get("x").getAsInt() + ox;
            int y = obj.get("y").getAsInt() + oy;
            int z = obj.get("z").getAsInt() + oz;
            String blockId = obj.get("id").getAsString();
            BlockPos targetPos = new BlockPos(x, y, z);
            Identifier id = Identifier.tryParse(blockId);
            if (id == null) continue;
            Block block = Registries.BLOCK.get(id);
            if (block == null || block == Blocks.AIR) continue;
            BlockState oldState = world.getBlockState(targetPos);
            BlockState newState = block.getDefaultState();
            if (oldState.getBlock() != block) {
                world.setBlockState(targetPos, newState, Block.NOTIFY_ALL);
                result.record.changes.add(new BlockChange(targetPos, oldState, newState));
                result.blocksPlaced++;
            }
        }
    }

    static void executeCommand(ServerWorld world, String command) {
        var server = world.getServer();
        if (server == null) return;
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd);
    }

    private static boolean placeSign(ServerWorld world, int x, int y, int z, String[] lines) {
        BlockPos pos = new BlockPos(x, y, z);
        world.setBlockState(pos, Blocks.OAK_SIGN.getDefaultState(), Block.NOTIFY_ALL);
        if (world.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            for (int i = 0; i < 4; i++) {
                String txt = (lines[i] != null) ? lines[i] : "";
                sign.setText(sign.getFrontText().withMessage(i, Text.literal(txt)), true);
            }
            sign.markDirty();
            world.updateListeners(pos, sign.getCachedState(), sign.getCachedState(), Block.NOTIFY_ALL);
            return true;
        }
        return false;
    }

    private static boolean fillChest(ServerWorld world, int x, int y, int z, JsonArray items) {
        BlockPos pos = new BlockPos(x, y, z);
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            chest.clear();
            for (JsonElement elem : items) {
                JsonObject item = elem.getAsJsonObject();
                String id = item.get("id").getAsString();
                int count = item.has("count") ? item.get("count").getAsInt() : 1;
                int slot = item.has("slot") ? item.get("slot").getAsInt() : -1;
                ItemStack stack = createItem(id, count);
                if (stack.isEmpty()) continue;
                if (slot >= 0 && slot < chest.size()) chest.setStack(slot, stack);
                else {
                    for (int s = 0; s < chest.size(); s++) {
                        if (chest.getStack(s).isEmpty()) { chest.setStack(s, stack); break; }
                    }
                }
            }
            chest.markDirty();
            return true;
        }
        return false;
    }

    private static ItemStack createItem(String id, int count) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) return ItemStack.EMPTY;
        var item = Registries.ITEM.get(identifier);
        if (item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item, count);
    }

    private static boolean placeCommandBlock(ServerWorld world, int x, int y, int z, String command, String mode, boolean conditional) {
        BlockPos pos = new BlockPos(x, y, z);
        Block block = Blocks.COMMAND_BLOCK;
        if ("REPEAT".equalsIgnoreCase(mode)) block = Blocks.REPEATING_COMMAND_BLOCK;
        else if ("CHAIN".equalsIgnoreCase(mode)) block = Blocks.CHAIN_COMMAND_BLOCK;
        world.setBlockState(pos, block.getDefaultState()
                .with(net.minecraft.block.CommandBlock.CONDITIONAL, conditional), Block.NOTIFY_ALL);
        if (world.getBlockEntity(pos) instanceof CommandBlockBlockEntity cmdBlock) {
            String cmd = command.startsWith("/") ? command.substring(1) : command;
            cmdBlock.getCommandExecutor().setCommand(cmd);
            cmdBlock.markDirty();
            world.updateListeners(pos, cmdBlock.getCachedState(), cmdBlock.getCachedState(), Block.NOTIFY_ALL);
            return true;
        }
        return false;
    }

    public static String executeShell(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new BufferedReader(new InputStreamReader(p.getInputStream()))
                    .lines().collect(Collectors.joining("\n"));
            p.waitFor();
            return output;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
