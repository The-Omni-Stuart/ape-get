package cz.cuni.mff.releasemanager.utils;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks free space before downloading or installing.
 */
public final class DiskSpace {

    private DiskSpace() {}

    /**
     * @param path path to inspect, or null when no check is needed
     * @param requiredBytes required free bytes
     * @return true when the path is null, the requirement is non-positive, or space is available
     */
    public static boolean hasAtLeast(Path path, long requiredBytes) {
        if (path == null || requiredBytes <= 0) {
            return true;
        }
        return usableSpace(path) >= requiredBytes;
    }

    /**
     * @param path path to inspect
     * @return usable bytes, or -1 when they cannot be determined
     */
    public static long usableSpace(Path path) {
        if (path == null) {
            return -1;
        }
        try {
            Path probe = Files.exists(path) ? path : path.toAbsolutePath().getParent();
            if (probe == null) {
                return -1;
            }
            FileStore store = Files.getFileStore(probe);
            return store.getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
