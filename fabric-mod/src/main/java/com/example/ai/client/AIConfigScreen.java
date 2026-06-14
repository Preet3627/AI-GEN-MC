package com.example.ai.client;

import com.example.ai.AIBuilderMod;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class AIConfigScreen extends Screen {
    private static final String[] PROVIDERS = {"ollama", "openai", "groq", "xai", "anthropic", "google"};
    private TextFieldWidget providerField;
    private TextFieldWidget modelField;
    private TextFieldWidget apiKeyField;
    private TextFieldWidget apiKeyProviderField;
    private String status = "";

    public AIConfigScreen(Screen parent) {
        super(Text.literal("AI Builder Config"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = 40;

        addDrawableChild(ButtonWidget.builder(
                Text.literal("<"),
                btn -> cycleProvider(-1)
        ).dimensions(cx - 100, y, 20, 20).build());

        providerField = new TextFieldWidget(textRenderer, cx - 75, y, 150, 20, Text.literal("Provider"));
        providerField.setText(AIBuilderMod.getConfig().selectedProvider);
        providerField.setEditable(false);
        addDrawableChild(providerField);

        addDrawableChild(ButtonWidget.builder(
                Text.literal(">"),
                btn -> cycleProvider(1)
        ).dimensions(cx + 80, y, 20, 20).build());

        y += 30;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Providers: ollama | openai | groq | xai | anthropic | google"),
                btn -> {}
        ).dimensions(cx - 100, y, 200, 15).build());

        y += 25;
        modelField = new TextFieldWidget(textRenderer, cx - 100, y, 200, 20, Text.literal("Model"));
        modelField.setText(AIBuilderMod.getConfig().selectedModel);
        modelField.setPlaceholder(Text.literal("Model name (empty = default)"));
        addDrawableChild(modelField);

        y += 30;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Set Model"),
                btn -> {
                    AIBuilderMod.getConfig().selectedModel = modelField.getText().trim();
                    AIBuilderMod.saveConfig();
                    status = "§aModel saved!";
                }
        ).dimensions(cx - 100, y, 200, 20).build());

        y += 30;
        apiKeyProviderField = new TextFieldWidget(textRenderer, cx - 100, y, 80, 20, Text.literal("Provider"));
        apiKeyProviderField.setText(AIBuilderMod.getConfig().selectedProvider);
        addDrawableChild(apiKeyProviderField);

        apiKeyField = new TextFieldWidget(textRenderer, cx - 15, y, 115, 20, Text.literal("API Key"));
        String currentKey = AIBuilderMod.getConfig().apiKeys.getOrDefault(
                AIBuilderMod.getConfig().selectedProvider, "");
        apiKeyField.setText(currentKey);
        addDrawableChild(apiKeyField);

        y += 30;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Set API Key"),
                btn -> {
                    String prov = apiKeyProviderField.getText().trim().toLowerCase();
                    String key = apiKeyField.getText().trim();
                    if (!prov.isEmpty() && !key.isEmpty()) {
                        AIBuilderMod.getConfig().apiKeys.put(prov, key);
                        AIBuilderMod.saveConfig();
                        status = "§aKey saved for " + prov;
                    }
                }
        ).dimensions(cx - 100, y, 200, 20).build());

        y += 30;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Done"),
                btn -> close()
        ).dimensions(cx - 100, y, 200, 20).build());
    }

    private void cycleProvider(int dir) {
        String current = AIBuilderMod.getConfig().selectedProvider;
        int idx = -1;
        for (int i = 0; i < PROVIDERS.length; i++) {
            if (PROVIDERS[i].equals(current)) { idx = i; break; }
        }
        int next = (idx + dir + PROVIDERS.length) % PROVIDERS.length;
        AIBuilderMod.getConfig().selectedProvider = PROVIDERS[next];
        AIBuilderMod.saveConfig();
        providerField.setText(PROVIDERS[next]);
        apiKeyField.setText(AIBuilderMod.getConfig().apiKeys.getOrDefault(PROVIDERS[next], ""));
        status = "§aProvider: " + PROVIDERS[next];
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("§lAI Builder Mod Config"), width / 2, 15, 0xffffff);
        if (!status.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, 160, 0xaaaaaa);
        }
    }
}
