package me.jamino.wynnlodgrabber;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ConflictScreen extends Screen {

    public ConflictScreen() {
        super(Component.literal("LOD Mod Conflict"));
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(
                        Component.literal("Quit Game"),
                        button -> Minecraft.getInstance().stop())
                .bounds(this.width / 2 - 75, this.height / 2 + 30, 150, 20)
                .build());
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, getTitle(), this.width / 2, this.height / 2 - 50, 0xFF5555);

        String[] lines = {
            "Both Distant Horizons and Voxy are installed.",
            "WynnLODGrabber requires only one LOD mod.",
            "Please remove one and restart Minecraft."
        };

        int lineHeight = this.font.lineHeight + 2;
        int startY = this.height / 2 - 20;
        for (int i = 0; i < lines.length; i++) {
            guiGraphics.drawCenteredString(this.font, Component.literal(lines[i]),
                    this.width / 2, startY + (i * lineHeight), 0xFFFFFF);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
