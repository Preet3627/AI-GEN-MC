package com.example.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ModConfig {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("ai-builder-mod.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String selectedProvider = "ollama";
    public String selectedModel = "";
    public Map<String, String> apiKeys = new HashMap<>();

    public static ModConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader r = Files.newBufferedReader(CONFIG_PATH)) {
                return GSON.fromJson(r, ModConfig.class);
            } catch (Exception e) {
                System.err.println("[AI] Failed to load config: " + e.getMessage());
            }
        }
        return new ModConfig();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer w = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            System.err.println("[AI] Failed to save config: " + e.getMessage());
        }
    }
}
