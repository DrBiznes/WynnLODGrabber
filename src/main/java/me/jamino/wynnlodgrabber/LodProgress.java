package me.jamino.wynnlodgrabber;

import java.util.Locale;
import java.util.concurrent.CancellationException;

/**
 * Thread-safe progress/state holder shared between a worker thread (download or install) and whatever
 * renders it (the progress toast, the install screen).
 */
public final class LodProgress {
    public enum Phase {
        PREPARING, DOWNLOADING, VERIFYING, EXTRACTING, READY,
        WAITING, INSTALLING, INSTALLED,
        FAILED, CANCELLED
    }

    public final String mod;
    public final String label;

    private volatile Phase phase = Phase.PREPARING;
    private volatile boolean cancelled;

    public volatile long done;
    public volatile long total;
    public volatile double bytesPerSec;
    /** Short transient status line, e.g. "Connection lost, retrying in 4s". Empty when nothing to say. */
    public volatile String detail = "";
    public volatile String error = "";
    /** Wall-clock time the phase last became terminal; used to time toast fade-out. */
    public volatile long finishedAt;

    public LodProgress(String mod, String label) {
        this.mod = mod;
        this.label = label;
    }

    public Phase phase() {
        return phase;
    }

    public void setPhase(Phase newPhase) {
        this.phase = newPhase;
        this.detail = "";
        this.done = 0;
        if (isTerminal()) {
            finishedAt = System.currentTimeMillis();
        }
    }

    /** Switches phase without resetting the byte counters (used when a new phase reuses the same bar). */
    public void setPhaseKeepingCounters(Phase newPhase) {
        this.phase = newPhase;
    }

    public boolean isTerminal() {
        return phase == Phase.READY || phase == Phase.INSTALLED
                || phase == Phase.FAILED || phase == Phase.CANCELLED;
    }

    public void cancel() {
        cancelled = true;
    }

    public void checkCancelled() {
        if (cancelled) throw new CancellationException("Cancelled");
    }

    public float fraction() {
        long t = total;
        if (t <= 0) return 0f;
        return Math.min(1f, Math.max(0f, (float) done / t));
    }

    public long etaSeconds() {
        double speed = bytesPerSec;
        if (speed < 1) return -1;
        return (long) Math.ceil((total - done) / speed);
    }

    public static String formatBytes(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
        if (bytes >= 1L << 10) return String.format(Locale.ROOT, "%.0f KB", bytes / (double) (1L << 10));
        return bytes + " B";
    }

    public static String formatSpeed(double bytesPerSec) {
        return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }

    public static String formatDuration(long seconds) {
        if (seconds < 0) return "--";
        if (seconds >= 3600) return String.format(Locale.ROOT, "%dh %dm", seconds / 3600, (seconds % 3600) / 60);
        if (seconds >= 60) return String.format(Locale.ROOT, "%dm %ds", seconds / 60, seconds % 60);
        return seconds + "s";
    }
}
