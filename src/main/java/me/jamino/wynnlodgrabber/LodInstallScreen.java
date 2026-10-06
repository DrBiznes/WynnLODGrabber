package me.jamino.wynnlodgrabber;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Replaces the generic "Disconnected" screen while LODs are being installed. Unlike that screen it always shows
 * what is happening and what to do next: a progress bar while working, then a rejoin countdown / buttons once the
 * install finished, or the error and a Retry button if it did not.
 */
public class LodInstallScreen extends Screen {
    private static final int REJOIN_DELAY_MS = 5000;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    private final LodInstallJob job;
    private Button primaryButton;
    private Button menuButton;

    /** When the automatic rejoin fires; 0 = not scheduled yet, -1 = cancelled by the player. */
    private long rejoinAt = 0;

    public LodInstallScreen(LodInstallJob job) {
        super(Component.literal("Installing LODs"));
        this.job = job;
    }

    @Override
    protected void init() {
        int buttonWidth = 150;
        int spacing = 6;
        int startX = (width - (buttonWidth * 2 + spacing)) / 2;
        int y = height / 2 + 40;

        primaryButton = addRenderableWidget(Button.builder(Component.empty(), button -> onPrimary())
                .bounds(startX, y, buttonWidth, 20)
                .build());
        menuButton = addRenderableWidget(Button.builder(Component.literal("Main Menu"), button -> goToMenu())
                .bounds(startX + buttonWidth + spacing, y, buttonWidth, 20)
                .build());
        updateButtons();
    }

    private void updateButtons() {
        if (primaryButton == null) return;
        LodProgress.Phase phase = job.progress.phase();
        boolean finished = phase == LodProgress.Phase.INSTALLED || phase == LodProgress.Phase.FAILED;
        primaryButton.visible = finished;
        primaryButton.active = finished;
        menuButton.visible = finished;
        menuButton.active = finished;

        if (phase == LodProgress.Phase.FAILED) {
            primaryButton.setMessage(Component.literal("Retry"));
        } else if (phase == LodProgress.Phase.INSTALLED) {
            primaryButton.setMessage(Component.literal(job.autoRejoin ? "Rejoin Wynncraft" : "Join Wynncraft"));
        }
    }

    private void onPrimary() {
        if (job.progress.phase() == LodProgress.Phase.FAILED) {
            job.retry();
        } else {
            rejoin();
        }
    }

    private void goToMenu() {
        Minecraft.getInstance().setScreen(new TitleScreen());
    }

    private void rejoin() {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = job.server != null
                ? job.server
                : new ServerData("Wynncraft", job.serverIp, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(), minecraft, ServerAddress.parseString(server.ip),
                server, false, null);
    }

    @Override
    public void tick() {
        super.tick();
        updateButtons();

        if (job.progress.phase() == LodProgress.Phase.INSTALLED && job.autoRejoin && rejoinAt == 0) {
            rejoinAt = System.currentTimeMillis() + REJOIN_DELAY_MS;
        }
        if (rejoinAt > 0 && System.currentTimeMillis() >= rejoinAt) {
            rejoinAt = -1;
            rejoin();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // GLFW_KEY_ESCAPE
            LodProgress.Phase phase = job.progress.phase();
            if (phase == LodProgress.Phase.INSTALLED) {
                if (rejoinAt > 0) {
                    rejoinAt = -1; // first Esc cancels the countdown, the screen stays
                } else {
                    goToMenu();
                }
            } else if (phase == LodProgress.Phase.FAILED) {
                goToMenu();
            }
            return true; // never close mid-install
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        LodProgress progress = job.progress;
        int cx = width / 2;
        int top = height / 2 - 50;
        int lineHeight = font.lineHeight + 3;

        switch (progress.phase()) {
            case WAITING -> {
                graphics.drawCenteredString(font, "Installing " + progress.label + " LODs", cx, top, WHITE);
                graphics.drawCenteredString(font, "Leaving the server safely...", cx, top + lineHeight * 2, WHITE);
                graphics.drawCenteredString(font, "Please keep the game open - this only takes a moment.",
                        cx, top + lineHeight * 3, GRAY);
                drawBar(graphics, cx, top + lineHeight * 5, progress.fraction(), GREEN);
            }
            case INSTALLING -> {
                graphics.drawCenteredString(font, "Installing " + progress.label + " LODs", cx, top, WHITE);
                String detail = progress.detail.isEmpty()
                        ? "Moving files into place..." : progress.detail;
                graphics.drawCenteredString(font, detail, cx, top + lineHeight * 2, WHITE);
                graphics.drawCenteredString(font, "Please keep the game open - this only takes a moment.",
                        cx, top + lineHeight * 3, GRAY);
                drawBar(graphics, cx, top + lineHeight * 5, Math.max(0.05f, progress.fraction()), GREEN);
            }
            case INSTALLED -> {
                graphics.drawCenteredString(font, "LODs installed!", cx, top, GREEN);
                graphics.drawCenteredString(font, "Rejoin Wynncraft to start using your new " + progress.label + " LODs.",
                        cx, top + lineHeight * 2, WHITE);
                if (rejoinAt > 0) {
                    long seconds = Math.max(1, (rejoinAt - System.currentTimeMillis() + 999) / 1000);
                    graphics.drawCenteredString(font, "Rejoining in " + seconds + "s...  (press Esc to cancel)",
                            cx, top + lineHeight * 3, GRAY);
                }
            }
            case FAILED -> {
                graphics.drawCenteredString(font, "LOD install failed", cx, top, RED);
                List<FormattedCharSequence> lines = font.split(Component.literal(progress.error), 320);
                int y = top + lineHeight * 2;
                for (FormattedCharSequence line : lines) {
                    graphics.drawCenteredString(font, line, cx, y, WHITE);
                    y += lineHeight;
                }
                graphics.drawCenteredString(font, "Your existing LOD data was left untouched.", cx, y + 2, GRAY);
            }
            default -> {
                graphics.drawCenteredString(font, "Preparing install...", cx, top, WHITE);
            }
        }
    }

    private void drawBar(GuiGraphics graphics, int cx, int y, float fraction, int color) {
        int barWidth = 220;
        int x = cx - barWidth / 2;
        graphics.fill(x - 1, y - 1, x + barWidth + 1, y + 7, 0xFF000000);
        graphics.fill(x, y, x + barWidth, y + 6, 0xFF2B2B3A);
        graphics.fill(x, y, x + (int) (barWidth * Math.min(1f, fraction)), y + 6, color);
    }
}
