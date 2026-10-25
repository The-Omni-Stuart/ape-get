package cz.cuni.mff.releasemanager.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// temp downloads used by all providers
public final class StagedAsset {

    private StagedAsset() {}

    public static Path create(String name) throws IOException {
        return FileUtils.createTempFilePath(name);
    }

    public static void requireContent(Path path, String name) throws IOException {
        if (Files.size(path) == 0) {
            throw new IOException("Downloaded file is empty: " + name);
        }
    }

    public static void discard(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
        FileUtils.removeTempDir(path);
    }
}
