package com.example.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class TerrainScanner {
    public static String scanArea(ServerWorld world, BlockPos center, int radius) {
        JsonArray blocksArray = new JsonArray();

        for (int x = -radius; x <= radius; x += 2) {
            for (int z = -radius; z <= radius; z += 2) {
                for (int y = -3; y <= 5; y++) {
                    BlockPos target = center.add(x, y, z);
                    BlockState state = world.getBlockState(target);

                    if (!state.isAir()) {
                        JsonObject blockInfo = new JsonObject();
                        blockInfo.addProperty("rel_x", x);
                        blockInfo.addProperty("rel_y", y);
                        blockInfo.addProperty("rel_z", z);
                        blockInfo.addProperty("id", Registries.BLOCK.getId(state.getBlock()).toString());
                        blocksArray.add(blockInfo);
                    }
                }
            }
        }
        return blocksArray.toString();
    }
}
