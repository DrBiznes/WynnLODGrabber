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
    /** Non-null when this prompt offers an update to already-installed LODs rather than a first download. */
    private final String updateVersion;

    public LodPromptScreen(Screen parent, String modLabel, Runnable onAccept, Runnable onDecline, Runnable onNotNow) {
        this(parent, modLabel, null, onAccept, onDecline, onNotNow);
    }

    public LodPromptScreen(Screen parent, String modLabel, String updateVersion,
                           Runnable onAccept, Runnable onDecline, Runnable onNotNow) {
        super(Component.literal((updateVersion != null ? "Wynncraft LOD Update — " : "Wynncraft LOD Download — ")
                + modLabel));
        this.parent = parent;
        this.modLabel = modLabel;
        this.updateVersion = updateVersion;
        this.onAccept = onAccept;
        this.onDecline = onDecline;
        this.onNotNow = onNotNow;
    }

    private Component label(String key, String updateText) {
        return updateVersion != null ? Component.literal(updateText) : Component.translatable(key);
    }

    @Override
    protected void init() {
        int buttonWidth = 150;
        int buttonHeight = 20;
        int spacing = 5;

        int totalWidth = buttonWidth * 3 + spacing * 2;
        int startX = (width - totalWidth) / 2;
        int y = height / 2 + 30;

        this.addRenderableWidget(Button.builder(
                        label("screen.wynnlodgrabber.accept", "Update LODs"),
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
                        label("screen.wynnlodgrabber.decline", "Skip This Version"),
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
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, getTitle(), width / 2, height / 2 - 40, 0xFFFFFFFF);

        LodManifest.Package pkg = LodManifest.current().get("Distant Horizons".equals(modLabel) ? "dh" : "voxy");
        String downloadSize = pkg != null ? LodProgress.formatBytes(pkg.size) : "a large file";
        String[] descriptionLines = updateVersion != null
                ? new String[] {
                        "Updated Wynncraft LODs are available for " + modLabel + " (" + updateVersion + ").",
                        "Updating replaces your current Wynncraft LODs with the latest ones.",
                        "The download is approximately " + downloadSize + " and runs in the background - keep playing!",
                        "Afterwards you'll be asked to reconnect once to finish installing."}
                : new String[] {
                        "Would you like to download the Wynncraft LODs for " + modLabel + "?",
                        "This will allow you to see further in the game.",
                        "The download is approximately " + downloadSize + " and runs in the background - keep playing!",
                        "Afterwards you'll be asked to reconnect once to finish installing."};

        int lineHeight = this.font.lineHeight + 2;
        int startY = height / 2 - 20;

        for (int i = 0; i < descriptionLines.length; i++) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.literal(descriptionLines[i]),
                    width / 2,
                    startY + (i * lineHeight),
                    0xFFFFFFFF
            );
        }
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
