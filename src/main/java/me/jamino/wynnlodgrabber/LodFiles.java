package me.jamino.wynnlodgrabber;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

final class LodFiles {
    private LodFiles() {}

    /** Recursively deletes {@code path} (file or directory). Individual failures are ignored. */
    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException ignored) {}
    }

    static void copyRecursively(Path src, Path dest) throws IOException {
        try (var stream = Files.walk(src)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path target = dest.resolve(src.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
