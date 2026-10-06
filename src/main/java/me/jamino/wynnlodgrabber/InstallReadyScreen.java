package me.jamino.wynnlodgrabber;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown once a download is finished and verified: choose to finish the install now or later. */
public class InstallReadyScreen extends Screen {
    private final String modLabel;
    private final Runnable onInstallNow;
    private final Runnable onLater;

    public InstallReadyScreen(String modLabel, Runnable onInstallNow, Runnable onLater) {
        super(Component.literal("LODs downloaded"));
        this.modLabel = modLabel;
        this.onInstallNow = onInstallNow;
        this.onLater = onLater;
    }

    @Override
    protected void init() {
        int buttonWidth = 170;
        int spacing = 6;
        int startX = (width - (buttonWidth * 2 + spacing)) / 2;
        int y = height / 2 + 30;

        addRenderableWidget(Button.builder(Component.literal("Install Now"), button -> {
                    Minecraft.getInstance().setScreen(null);
                    onInstallNow.run();
                })
                .bounds(startX, y, buttonWidth, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Install When I Leave"), button -> {
                    Minecraft.getInstance().setScreen(null);
                    onLater.run();
                })
                .bounds(startX + buttonWidth + spacing, y, buttonWidth, 20)
                .build());
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(font, "Your " + modLabel + " LODs are ready!", width / 2, height / 2 - 40, 0xFF55FF55);

        String[] lines = {
                "To finish, the game needs to leave Wynncraft for a few seconds.",
                "It will then reconnect you automatically.",
                "Or pick \"Install When I Leave\" and nothing happens until you exit the server."
        };
        int lineHeight = font.lineHeight + 2;
        for (int i = 0; i < lines.length; i++) {
            guiGraphics.drawCenteredString(font, Component.literal(lines[i]), width / 2,
                    height / 2 - 20 + i * lineHeight, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        onLater.run();
        Minecraft.getInstance().setScreen(null);
    }
}
