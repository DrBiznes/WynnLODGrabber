package me.jamino.wynnlodgrabber;

import net.minecraft.client.multiplayer.ServerData;

import java.nio.file.Path;

/**
 * Runs the "swap staged LODs into place" step on a background thread and exposes its state to
 * {@link LodInstallScreen}.
 */
public final class LodInstallJob {
    public final String mod;
    public final String serverIp;
    public final String version;
    public final Path staging;
    /** Whether the screen should reconnect to the server on its own once the install finished. */
    public final boolean autoRejoin;
    /** The server to rejoin; may be null, in which case one is built from {@link #serverIp}. */
    public final ServerData server;
    public final LodProgress progress;

    private final int settleSeconds;
    private Thread thread;

    public LodInstallJob(String mod, String serverIp, String version, Path staging,
                         boolean autoRejoin, int settleSeconds, ServerData server) {
        this.mod = mod;
        this.serverIp = serverIp;
        this.version = version;
        this.staging = staging;
        this.autoRejoin = autoRejoin;
        this.settleSeconds = settleSeconds;
        this.server = server;
        this.progress = new LodProgress(mod, "dh".equals(mod) ? "Distant Horizons" : "Voxy");
    }

    public synchronized void start() {
        startThread(settleSeconds);
    }

    /** Re-runs a failed install immediately. */
    public synchronized void retry() {
        if (progress.phase() == LodProgress.Phase.FAILED) {
            startThread(0);
        }
    }

    public boolean isRunning() {
        LodProgress.Phase phase = progress.phase();
        return phase == LodProgress.Phase.WAITING || phase == LodProgress.Phase.INSTALLING;
    }

    private void startThread(int settle) {
        if (isRunning()) return;
        progress.error = "";
        progress.setPhase(settle > 0 ? LodProgress.Phase.WAITING : LodProgress.Phase.INSTALLING);
        thread = new Thread(() -> run(settle), "WynnLOD-Installer");
        thread.setDaemon(true);
        thread.start();
    }

    private void run(int settle) {
        try {
            // Give the LOD mod time to finish shutting down its level and release the GPU/database before
            // we touch its files (Apple Silicon crashes if its Metal callbacks outlive freed objects).
            if (settle > 0) {
                progress.total = settle;
                for (int i = 0; i < settle; i++) {
                    progress.done = i;
                    Thread.sleep(1000);
                }
                progress.setPhase(LodProgress.Phase.INSTALLING);
            }

            Path target = LodInstaller.targetDir(mod, serverIp);
            Wynnlodgrabber.LOGGER.info("Installing staged {} LODs into {}", mod, target);
            LodInstaller.install(staging, target, progress);

            Wynnlodgrabber.onInstallCompleted(this);
            progress.setPhase(LodProgress.Phase.INSTALLED);
            Wynnlodgrabber.LOGGER.info("LOD installation complete for {} on {}", mod, serverIp);
        } catch (Throwable t) {
            Wynnlodgrabber.LOGGER.error("LOD installation failed", t);
            String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            progress.error = message;
            progress.setPhase(LodProgress.Phase.FAILED);
        }
    }
}
