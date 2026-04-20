package me.jamino.wynnlodgrabber;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class LodPromptScreen extends Screen {
    private final Screen parent;
    private final Runnable onAccept;
    private final Runnable onDecline;
    private final Runnable onNotNow;
    private final String modLabel;

    public LodPromptScreen(Screen parent, String modLabel, Runnable onAccept, Runnable onDecline, Runnable onNotNow) {
        super(Component.literal("Wynncraft LOD Download — " + modLabel));
        this.parent = parent;
        this.modLabel = modLabel;
        this.onAccept = onAccept;
        this.onDecline = onDecline;
        this.onNotNow = onNotNow;
    }

    @Override
    protected void init() {
        int buttonWidth = 150;
        int buttonHeight = 20;
        int spacing = 5;

        int totalWidth = buttonWidth * 3 + spacing * 2;
        int startX = (width - totalWidth) / 2;
        int y = height / 2 + 20;

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.wynnlodgrabber.accept"),
                        button -> {
                            onAccept.run();
                            Minecraft.getInstance().setScreen(null);
                        })
                .bounds(startX, y, buttonWidth, buttonHeight)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.wynnlodgrabber.notnow"),
                        button -> {
                            onNotNow.run();
                            Minecraft.getInstance().setScreen(null);
                        })
                .bounds(startX + buttonWidth + spacing, y, buttonWidth, buttonHeight)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.wynnlodgrabber.decline"),
                        button -> {
                            onDecline.run();
                            Minecraft.getInstance().setScreen(null);
                        })
                .bounds(startX + (buttonWidth + spacing) * 2, y, buttonWidth, buttonHeight)
                .build());
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, getTitle(), width / 2, height / 2 - 40, 0xFFFFFF);

        String[] descriptionLines = {
                "Would you like to download the Wynncraft LODs for " + modLabel + "?",
                "This will allow you to see further in the game.",
                "The download is approximately 1.5GB."
        };

        int lineHeight = this.font.lineHeight + 2;
        int startY = height / 2 - 20;

        for (int i = 0; i < descriptionLines.length; i++) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.literal(descriptionLines[i]),
                    width / 2,
                    startY + (i * lineHeight),
                    0xFFFFFF
            );
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        onNotNow.run();
        Minecraft.getInstance().setScreen(null);
    }
}
