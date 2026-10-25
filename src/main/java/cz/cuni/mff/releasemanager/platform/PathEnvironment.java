package cz.cuni.mff.releasemanager.platform;

import java.io.File;
import java.nio.file.Path;

/**
 * Checks whether a directory is visible on the executable search path.
 */
public final class PathEnvironment {

    private PathEnvironment() {}

    /**
     * @param directory directory to look for
     * @return true when the directory is listed in {@code PATH}
     */
    public static boolean isOnPath(Path directory) {
        return contains(System.getenv("PATH"), directory, File.pathSeparator);
    }

    /**
     * @param pathValue raw search path
     * @param directory directory to look for
     * @param separator platform path separator
     * @return true when the directory appears as a normalized entry
     */
    public static boolean contains(String pathValue, Path directory, String separator) {
        if (pathValue == null || directory == null) {
            return false;
        }
        Path normalized = directory.toAbsolutePath().normalize();
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                if (Path.of(entry).toAbsolutePath().normalize().equals(normalized)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // skip malformed entries
            }
        }
        return false;
    }
}
