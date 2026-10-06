package me.jamino.wynnlodgrabber;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/** Corner toast showing background download progress, so players can keep playing and don't need chat spam. */
public class LodDownloadToast implements Toast {
    private static final int WIDTH = 250;

    private static final int BACKGROUND = 0xF0141420;
    private static final int BORDER = 0xFF3A3A55;
    private static final int BAR_BACKGROUND = 0xFF2B2B3A;
    private static final int BAR_PROGRESS = 0xFF4FC3F7;
    private static final int BAR_DONE = 0xFF66BB6A;
    private static final int BAR_FAILED = 0xFFEF5350;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFB0B0C0;

    private static final long LINGER_READY_MS = 6_000;
    private static final long LINGER_FAILED_MS = 10_000;

    private final LodProgress progress;
    private volatile Visibility visibility = Visibility.SHOW;

    public LodDownloadToast(LodProgress progress) {
        this.progress = progress;
    }

    @Override
    public Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public void update(ToastManager toastManager, long visibleTime) {
        LodProgress.Phase phase = progress.phase();
        if (!progress.isTerminal()) return;

        long linger = switch (phase) {
            case READY -> LINGER_READY_MS;
            case FAILED -> LINGER_FAILED_MS;
            default -> 0;
        };
        if (System.currentTimeMillis() - progress.finishedAt >= linger) {
            visibility = Visibility.HIDE;
        }
    }

    @Override
    public void render(GuiGraphics graphics, Font font, long visibleTime) {
        int w = width();
        int h = height();

        graphics.fill(0, 0, w, h, BACKGROUND);
        graphics.fill(0, 0, w, 1, BORDER);
        graphics.fill(0, h - 1, w, h, BORDER);
        graphics.fill(0, 0, 1, h, BORDER);
        graphics.fill(w - 1, 0, w, h, BORDER);

        LodProgress.Phase phase = progress.phase();
        String title;
        String line;
        int barColor = BAR_PROGRESS;
        float fraction = progress.fraction();

        switch (phase) {
            case DOWNLOADING -> {
                title = "Downloading " + progress.label + " LODs";
                String detail = progress.detail;
                if (!detail.isEmpty()) {
                    line = detail;
                } else {
                    long eta = progress.etaSeconds();
                    line = LodProgress.formatBytes(progress.done) + " / " + LodProgress.formatBytes(progress.total)
                            + (progress.bytesPerSec > 0
                                    ? "  -  " + LodProgress.formatSpeed(progress.bytesPerSec)
                                            + (eta >= 0 ? "  -  " + LodProgress.formatDuration(eta) + " left" : "")
                                    : "");
                }
            }
            case VERIFYING -> {
                title = "Verifying download";
                line = Math.round(fraction * 100) + "%";
            }
            case EXTRACTING -> {
                title = "Preparing " + progress.label + " LODs";
                line = Math.round(fraction * 100) + "%  -  almost there";
            }
            case READY -> {
                title = progress.label + " LODs ready to install";
                line = "You'll be asked to reconnect to finish.";
                fraction = 1f;
                barColor = BAR_DONE;
            }
            case FAILED -> {
                title = "LOD download failed";
                line = progress.error;
                barColor = BAR_FAILED;
            }
            default -> {
                title = "Preparing download";
                line = progress.detail.isEmpty() ? "Contacting server..." : progress.detail;
                fraction = 0f;
            }
        }

        graphics.drawString(font, font.plainSubstrByWidth(title, w - 16), 8, 5, TEXT);
        graphics.drawString(font, font.plainSubstrByWidth(line == null ? "" : line, w - 16), 8, 15, TEXT_DIM);

        int barX = 8;
        int barY = h - 8;
        int barW = w - 16;
        graphics.fill(barX, barY, barX + barW, barY + 3, BAR_BACKGROUND);
        graphics.fill(barX, barY, barX + (int) (barW * fraction), barY + 3, barColor);
    }
}
