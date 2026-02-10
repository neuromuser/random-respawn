package com.neuromuser.randomrespawn;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class RespawnLoadingScreen extends Screen {
    private String currentKey = "randomrespawn.please_wait";
    private int progress = 0;
    private int ticksOpen = 0;
    private static final int MAX_TICKS_BEFORE_AUTO_CLOSE = 600;

    public RespawnLoadingScreen() {
        super(Text.translatable("randomrespawn.please_wait"));
    }

    public void updateProgress(String key, int progress) {
        this.currentKey = key;
        this.progress = progress;
        this.ticksOpen = 0;
    }

    @Override
    public void tick() {
        super.tick();
        ticksOpen++;

        if (ticksOpen >= MAX_TICKS_BEFORE_AUTO_CLOSE) {
            if (client != null) {
                client.setScreen(null);
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackgroundTexture(context);

        Text statusText = Text.translatable(currentKey, progress);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, statusText, centerX, centerY, 0xFFFFFF);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}