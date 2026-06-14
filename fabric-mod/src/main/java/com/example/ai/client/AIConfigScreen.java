package com.example.ai.client;

import com.example.ai.AIBuilderMod;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class AIConfigScreen extends Screen {
    private static final String[] PROVIDERS = {"ollama", "openai", "groq", "xai", "anthropic", "google"};
    private TextFieldWidget modelField;
    private TextFieldWidget apiKeyField;
    private TextFieldWidget apiKeyProviderField;
    private ButtonWidget chatToggleBtn;
    private ButtonWidget prevProviderBtn;
    private ButtonWidget nextProviderBtn;
    private String status = "";
    private int statusTimer = 0;

    public AIConfigScreen(Screen parent) {
        super(Text.literal("AI Builder Config"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = 35;

        // === SECTION: Provider ===
        drawSectionHeader(y, "§6§lPROVIDER");
        y += 14;

        prevProviderBtn = ButtonWidget.builder(
                Text.literal("◀"),
                btn -> cycleProvider(-1)
        ).dimensions(cx - 110, y, 25, 20).build();
        addDrawableChild(prevProviderBtn);

        addDrawableChild(ButtonWidget.builder(
                Text.literal(AIBuilderMod.getConfig().selectedProvider),
                btn -> {}
        ).dimensions(cx - 80, y, 160, 20).build());

        nextProviderBtn = ButtonWidget.builder(
                Text.literal("▶"),
                btn -> cycleProvider(1)
        ).dimensions(cx + 85, y, 25, 20).build();
        addDrawableChild(nextProviderBtn);

        y += 24;
        String providerList = String.join(" §7|§f ", PROVIDERS);
        addDrawableChild(ButtonWidget.builder(
                Text.literal(providerList),
                btn -> {}
        ).dimensions(cx - 110, y, 220, 12).build());

        // === SECTION: Model ===
        y += 24;
        drawSectionHeader(y, "§b§lMODEL");
        y += 14;

        modelField = new TextFieldWidget(textRenderer, cx - 110, y, 220, 20, Text.literal("Model name"));
        modelField.setText(AIBuilderMod.getConfig().selectedModel);
        modelField.setPlaceholder(Text.literal("Model name (empty = default)"));
        addDrawableChild(modelField);

        y += 24;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("§lSET MODEL"),
                btn -> {
                    AIBuilderMod.getConfig().selectedModel = modelField.getText().trim();
                    AIBuilderMod.saveConfig();
                    setStatus("§a✔ Model saved!");
                }
        ).dimensions(cx - 110, y, 220, 20).build());

        // === SECTION: API Key ===
        y += 28;
        drawSectionHeader(y, "§e§lAPI KEY");
        y += 14;

        apiKeyProviderField = new TextFieldWidget(textRenderer, cx - 110, y, 70, 20, Text.literal("Provider"));
        apiKeyProviderField.setText(AIBuilderMod.getConfig().selectedProvider);
        addDrawableChild(apiKeyProviderField);

        apiKeyField = new TextFieldWidget(textRenderer, cx - 35, y, 145, 20, Text.literal("API Key"));
        String currentKey = AIBuilderMod.getConfig().apiKeys.getOrDefault(
                AIBuilderMod.getConfig().selectedProvider, "");
        apiKeyField.setText(currentKey);
        apiKeyField.setPlaceholder(Text.literal("Enter API key..."));
        addDrawableChild(apiKeyField);

        y += 24;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("§lSET API KEY"),
                btn -> {
                    String prov = apiKeyProviderField.getText().trim().toLowerCase();
                    String key = apiKeyField.getText().trim();
                    if (!prov.isEmpty() && !key.isEmpty()) {
                        AIBuilderMod.getConfig().apiKeys.put(prov, key);
                        AIBuilderMod.saveConfig();
                        setStatus("§a✔ Key saved for " + prov);
                    } else {
                        setStatus("§cProvider or key is empty!");
                    }
                }
        ).dimensions(cx - 110, y, 220, 20).build());

        // === SECTION: Chat Responses ===
        y += 28;
        drawSectionHeader(y, "§d§lCHAT RESPONSES");
        y += 14;

        chatToggleBtn = ButtonWidget.builder(
                buildChatToggleText(),
                btn -> {
                    AIBuilderMod.getConfig().chatEnabled = !AIBuilderMod.getConfig().chatEnabled;
                    AIBuilderMod.saveConfig();
                    chatToggleBtn.setMessage(buildChatToggleText());
                    setStatus("§aChat " + (AIBuilderMod.getConfig().chatEnabled ? "enabled" : "disabled"));
                }
        ).dimensions(cx - 110, y, 220, 20).build();
        addDrawableChild(chatToggleBtn);

        // === Done button ===
        y += 30;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("§lDONE"),
                btn -> close()
        ).dimensions(cx - 110, y, 220, 20).build());
    }

    private Text buildChatToggleText() {
        boolean on = AIBuilderMod.getConfig().chatEnabled;
        return Text.literal("§" + (on ? "a" : "c") + (on ? "ON" : "OFF") + " §7- Click to toggle");
    }

    private void drawSectionHeader(int y, String text) {
        // Background bar
        int cx = width / 2;
        addDrawableChild(ButtonWidget.builder(
                Text.literal(""),
                btn -> {}
        ).dimensions(cx - 120, y, 240, 2).build());
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
        apiKeyField.setText(AIBuilderMod.getConfig().apiKeys.getOrDefault(PROVIDERS[next], ""));
        apiKeyProviderField.setText(PROVIDERS[next]);
        setStatus("§aProvider: " + PROVIDERS[next]);
    }

    private void setStatus(String msg) {
        status = msg;
        statusTimer = 60;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = width / 2;

        // Title bar
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("§l§nAI Builder Mod Config"), cx, 12, 0xffaa00);

        // Section labels (draw directly because ButtonWidget labels are limited)
        context.drawTextWithShadow(textRenderer, Text.literal("§6§lPROVIDER"), cx - 110, 35, 0xffffff);

        String prov = AIBuilderMod.getConfig().selectedProvider;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("§f" + prov.toUpperCase()), cx, 50, 0xffffff);

        context.drawTextWithShadow(textRenderer, Text.literal("§b§lMODEL"), cx - 110, 90, 0xffffff);
        context.drawTextWithShadow(textRenderer, Text.literal("§e§lAPI KEY"), cx - 110, 143, 0xffffff);
        context.drawTextWithShadow(textRenderer, Text.literal("§d§lCHAT RESPONSES"), cx - 110, 196, 0xffffff);

        // Status message
        if (!status.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), cx, height - 30, 0xaaaaaa);
            if (--statusTimer <= 0) status = "";
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
