package me.jamino.wynnlodgrabber;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public class LodPromptScreen extends Screen {
    private final Screen parent;
    private final Runnable onAccept;
    private final Runnable onDecline;
    private final Runnable onNotNow;
    private static final int PADDING = 10;

    public LodPromptScreen(Screen parent, Runnable onAccept, Runnable onDecline, Runnable onNotNow) {
        super(Text.translatable("screen.wynnlodgrabber.title"));
        this.parent = parent;
        this.onAccept = onAccept;
        this.onDecline = onDecline;
        this.onNotNow = onNotNow;
    }

    @Override
    protected void init() {
        int buttonWidth = 150;
        int buttonHeight = 20;
        int spacing = 5;

        // Calculate total width needed for three buttons
        int totalWidth = buttonWidth * 3 + spacing * 2;
        int startX = (width - totalWidth) / 2;
        int y = height / 2 + 20;

        // Accept button
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.wynnlodgrabber.accept"),
                        button -> {
                            onAccept.run();
                            close();
                        })
                .dimensions(startX, y, buttonWidth, buttonHeight)
                .build());

        // Not Right Now button
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.wynnlodgrabber.notnow"),
                        button -> {
                            onNotNow.run();
                            close();
                        })
                .dimensions(startX + buttonWidth + spacing, y, buttonWidth, buttonHeight)
                .build());

        // Decline button
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.wynnlodgrabber.decline"),
                        button -> {
                            onDecline.run();
                            close();
                        })
                .dimensions(startX + (buttonWidth + spacing) * 2, y, buttonWidth, buttonHeight)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        // Draw the title
        context.drawCenteredTextWithShadow(textRenderer, getTitle(), width / 2, height / 2 - 40, 0xFFFFFF);

        // Draw the description
        String[] descriptionLines = {
                "Would you like to download the Wynncraft LODs?",
                "This will allow you to see further in the game.",
                "The download is approximately 1.5GB."
        };

        int lineHeight = textRenderer.fontHeight + 2;
        int startY = height / 2 - 20;

        for (int i = 0; i < descriptionLines.length; i++) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.literal(descriptionLines[i]),
                    width / 2,
                    startY + (i * lineHeight),
                    0xFFFFFF
            );
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
        onNotNow.run(); // Treat ESC same as clicking "Not Right Now"
    }
}