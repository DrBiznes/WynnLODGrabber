package me.jamino.wynnlodgrabber;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads a LOD package to disk and unpacks it into a staging directory.
 * <ul>
 *   <li>Resumable: the partial file is kept and continued with an HTTP Range request, even across restarts.</li>
 *   <li>Resilient: stalls and dropped connections are retried with backoff without losing progress.</li>
 *   <li>Verified: the finished file must match the manifest's SHA-256 before anything is extracted.</li>
 * </ul>
 */
final class LodDownloader {
    private static final int MAX_FAILED_ATTEMPTS = 6;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long DISK_MARGIN = 256L * 1024 * 1024;

    private LodDownloader() {}

    static Path stagingDir(Path configDir, String mod) {
        return configDir.resolve("staging_" + mod);
    }

    /**
     * @return the staging directory containing the extracted package
     * @throws CancellationException if {@link LodProgress#cancel()} was called
     */
    static Path download(LodManifest.Package pkg, String mod, Path configDir, LodProgress progress) throws IOException {
        Path downloadsDir = configDir.resolve("downloads");
        Files.createDirectories(downloadsDir);

        String partName = mod + "-" + pkg.sha256.substring(0, 12).toLowerCase(Locale.ROOT) + ".zip.part";
        Path part = downloadsDir.resolve(partName);
        purgeOtherDownloads(downloadsDir, partName);

        checkDiskSpace(downloadsDir, pkg, part);

        for (int round = 0; ; round++) {
            progress.setPhase(LodProgress.Phase.DOWNLOADING);
            progress.total = pkg.size;
            fetchWithResume(pkg, part, progress);

            progress.setPhase(LodProgress.Phase.VERIFYING);
            String actual = sha256(part, progress);
            if (actual.equalsIgnoreCase(pkg.sha256)) break;

            Wynnlodgrabber.LOGGER.warn("Checksum mismatch for {} (expected {}, got {})", mod, pkg.sha256, actual);
            Files.deleteIfExists(part);
            if (round >= 1) {
                throw new IOException("The downloaded file is corrupt (checksum mismatch). Please try again.");
            }
        }

        Path staging = stagingDir(configDir, mod);
        LodFiles.deleteRecursively(staging);
        Files.createDirectories(staging);

        progress.setPhase(LodProgress.Phase.EXTRACTING);
        try {
            extract(part, staging, progress);
        } catch (IOException | RuntimeException e) {
            LodFiles.deleteQuietly(staging);
            throw e;
        }
        Files.deleteIfExists(part);
        return staging;
    }

    private static void purgeOtherDownloads(Path downloadsDir, String keepName) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(downloadsDir)) {
            for (Path file : stream) {
                if (!file.getFileName().toString().equals(keepName)) {
                    LodFiles.deleteQuietly(file);
                }
            }
        }
    }

    private static void checkDiskSpace(Path dir, LodManifest.Package pkg, Path part) {
        try {
            long have = Files.isRegularFile(part) ? Files.size(part) : 0;
            long extracted = pkg.extractedSize > 0 ? pkg.extractedSize : pkg.size;
            long needed = Math.max(0, pkg.size - have) + extracted + DISK_MARGIN;
            long free = Files.getFileStore(dir).getUsableSpace();
            if (free < needed) {
                throw new IllegalStateException("Not enough free disk space: " + LodProgress.formatBytes(needed)
                        + " needed, " + LodProgress.formatBytes(free) + " available.");
            }
        } catch (IOException ignored) {
            // Can't determine free space; let the download try.
        }
    }

    // ---- download ---------------------------------------------------------------------------------

    private static void fetchWithResume(LodManifest.Package pkg, Path part, LodProgress progress) throws IOException {
        int failures = 0;
        while (true) {
            progress.checkCancelled();

            long have = Files.isRegularFile(part) ? Files.size(part) : 0;
            if (have == pkg.size) {
                progress.done = pkg.size;
                return;
            }
            if (have > pkg.size) {
                Files.delete(part);
                have = 0;
            }
            progress.done = have;

            try {
                long reached = attempt(pkg, part, have, progress);
                if (reached == pkg.size) return;
                throw new IOException("Connection closed before the download finished");
            } catch (IOException e) {
                progress.checkCancelled();
                long now = Files.isRegularFile(part) ? Files.size(part) : 0;
                // Only count attempts that made no headway, so a flaky-but-moving connection can finish.
                failures = now > have ? 1 : failures + 1;
                if (failures >= MAX_FAILED_ATTEMPTS) throw e;

                int waitSeconds = Math.min(15, 2 * failures);
                Wynnlodgrabber.LOGGER.warn("Download interrupted ({}), retrying in {}s", e.toString(), waitSeconds);
                backoff(waitSeconds, "Connection problem, retrying", failures, progress);
            }
        }
    }

    private static void backoff(int seconds, String message, int attempt, LodProgress progress) {
        progress.bytesPerSec = 0;
        for (int remaining = seconds; remaining > 0; remaining--) {
            progress.detail = message + " in " + remaining + "s (" + attempt + "/" + (MAX_FAILED_ATTEMPTS - 1) + ")";
            for (int i = 0; i < 4; i++) {
                progress.checkCancelled();
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Interrupted");
                }
            }
        }
        progress.detail = "";
    }

    /** One connection attempt, appending to {@code part}. Returns the file size when the stream ended. */
    private static long attempt(LodManifest.Package pkg, Path part, long have, LodProgress progress)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(pkg.url).toURL().openConnection();
        connection.setRequestProperty("User-Agent", "WynnLODGrabber Mod");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(20_000);
        if (have > 0) {
            connection.setRequestProperty("Range", "bytes=" + have + "-");
        }

        try {
            int code = connection.getResponseCode();
            boolean append;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                String contentRange = connection.getHeaderField("Content-Range");
                if (contentRange == null || !contentRange.startsWith("bytes " + have + "-")) {
                    throw new IOException("Server returned an unexpected range: " + contentRange);
                }
                append = true;
            } else if (code == HttpURLConnection.HTTP_OK) {
                // Fresh download, or the server ignored our Range header: start over.
                append = false;
                have = 0;
                progress.done = 0;
            } else if (code == 416) {
                Files.deleteIfExists(part);
                throw new IOException("Server rejected the resume request, restarting the download");
            } else {
                throw new IOException("Server returned HTTP " + code);
            }

            StandardOpenOption[] options = append
                    ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE};

            long written = have;
            long sampleTime = System.nanoTime();
            long sampleBytes = written;
            byte[] buffer = new byte[BUFFER_SIZE];

            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(part, options)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    progress.checkCancelled();
                    written += read;
                    if (written > pkg.size) {
                        throw new IOException("Server sent more data than expected");
                    }
                    out.write(buffer, 0, read);
                    progress.done = written;
                    if (!progress.detail.isEmpty()) progress.detail = "";

                    long now = System.nanoTime();
                    if (now - sampleTime >= 500_000_000L) {
                        double instant = (written - sampleBytes) / ((now - sampleTime) / 1e9);
                        progress.bytesPerSec = progress.bytesPerSec <= 0
                                ? instant
                                : progress.bytesPerSec * 0.7 + instant * 0.3;
                        sampleTime = now;
                        sampleBytes = written;
                    }
                }
            }
            return written;
        } finally {
            connection.disconnect();
        }
    }

    // ---- verify / extract -------------------------------------------------------------------------

    private static String sha256(Path file, LodProgress progress) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        progress.total = Files.size(file);
        byte[] buffer = new byte[BUFFER_SIZE];
        long done = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                progress.checkCancelled();
                digest.update(buffer, 0, read);
                done += read;
                progress.done = done;
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    private static void extract(Path zip, Path stagingDir, LodProgress progress) throws IOException {
        Path root = stagingDir.toAbsolutePath().normalize();
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            long total = 0;
            for (Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements(); ) {
                total += Math.max(0, e.nextElement().getSize());
            }
            progress.total = total;

            byte[] buffer = new byte[BUFFER_SIZE];
            long done = 0;
            for (Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements(); ) {
                progress.checkCancelled();
                ZipEntry entry = e.nextElement();
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Refusing to extract unsafe path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zipFile.getInputStream(entry);
                     OutputStream os = Files.newOutputStream(out)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        progress.checkCancelled();
                        os.write(buffer, 0, read);
                        done += read;
                        progress.done = done;
                    }
                }
            }
        }
    }
}
