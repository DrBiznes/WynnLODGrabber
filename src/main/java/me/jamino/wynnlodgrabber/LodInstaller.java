package me.jamino.wynnlodgrabber;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Swaps an extracted, staged LOD package into the LOD mod's data directory.
 * <p>
 * Each top-level entry of the package (a dimension folder) replaces the existing folder of the same name as a
 * whole, so no stale files (old RocksDB .sst files, SQLite -wal/-shm) can mix with the new data. The old folder is
 * kept until the new one is in place and restored if anything goes wrong.
 */
final class LodInstaller {
    private static final String OLD_SUFFIX = ".wlg_old";
    private static final int LOCK_RETRIES = 40;

    private LodInstaller() {}

    static Path targetDir(String mod, String serverIp) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        if ("dh".equals(mod)) {
            return gameDir.resolve("Distant_Horizons_server_data").resolve(serverIp.replace(".", "%2E"));
        }
        return gameDir.resolve(".voxy/saves").resolve(serverIp);
    }

    static void install(Path staging, Path target, LodProgress progress) throws IOException {
        Files.createDirectories(target);
        recoverInterruptedSwap(target);

        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(staging)) {
            stream.forEach(entries::add);
        }
        entries.sort(Comparator.comparing(Path::toString));
        if (entries.isEmpty()) {
            throw new IOException("The downloaded package is empty");
        }

        progress.total = entries.size();
        progress.done = 0;
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            Path dest = target.resolve(name);
            Path old = target.resolve(name + OLD_SUFFIX);

            retryWhileLocked(progress, () -> swap(entry, dest, old));
            progress.done++;
        }

        LodFiles.deleteQuietly(staging);
    }

    private static void swap(Path staged, Path dest, Path old) throws IOException {
        LodFiles.deleteRecursively(old);

        boolean hadExisting = Files.exists(dest);
        if (hadExisting) {
            Files.move(dest, old);
        }
        try {
            moveTree(staged, dest);
        } catch (IOException | RuntimeException e) {
            // Put things back the way they were before reporting the failure.
            LodFiles.deleteQuietly(dest);
            if (hadExisting) {
                try {
                    Files.move(old, dest);
                } catch (IOException restoreError) {
                    Wynnlodgrabber.LOGGER.error("Failed to restore previous LOD data from {}", old, restoreError);
                }
            }
            throw e;
        }
        if (hadExisting) {
            LodFiles.deleteQuietly(old);
        }
    }

    private static void moveTree(Path src, Path dest) throws IOException {
        try {
            Files.move(src, dest);
        } catch (DirectoryNotEmptyException crossVolume) {
            // Staging and target are on different volumes: copy, then drop the staged copy.
            LodFiles.copyRecursively(src, dest);
            LodFiles.deleteQuietly(src);
        }
    }

    /** If a previous install was killed halfway, put the old data back (or drop leftovers). */
    private static void recoverInterruptedSwap(Path target) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target, "*" + OLD_SUFFIX)) {
            for (Path old : stream) {
                String name = old.getFileName().toString();
                Path dest = target.resolve(name.substring(0, name.length() - OLD_SUFFIX.length()));
                if (Files.exists(dest)) {
                    LodFiles.deleteQuietly(old);
                } else {
                    Files.move(old, dest, StandardCopyOption.ATOMIC_MOVE);
                }
            }
        }
    }

    private interface IoAction {
        void run() throws IOException;
    }

    /**
     * The LOD mod may still hold its database open for a moment after the world closes (Windows refuses to move
     * locked files), so retry for a while before giving up.
     */
    private static void retryWhileLocked(LodProgress progress, IoAction action) throws IOException {
        FileSystemException last = null;
        for (int attempt = 1; attempt <= LOCK_RETRIES; attempt++) {
            try {
                action.run();
                progress.detail = "";
                return;
            } catch (NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                last = e;
                progress.detail = "Waiting for the LOD mod to release its files... (" + attempt + "/" + LOCK_RETRIES + ")";
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", ie);
                }
            }
        }
        throw new IOException("Files are still in use by another program: " + last.getFile()
                + ". Close anything using your LOD data and press Retry.", last);
    }
}
