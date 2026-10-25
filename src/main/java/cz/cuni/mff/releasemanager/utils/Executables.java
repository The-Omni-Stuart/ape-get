package cz.cuni.mff.releasemanager.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds external executables on {@code PATH}.
 */
public final class Executables {

    private Executables() {}

    /**
     * @param name executable name, with or without an extension
     * @return true when a runnable file with that name exists on PATH
     */
    public static boolean isAvailable(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return false;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(name);
        String pathExt = System.getenv("PATHEXT");
        if (pathExt != null && !pathExt.isBlank()) {
            for (String extension : pathExt.split(";")) {
                if (!extension.isBlank()) {
                    candidates.add(name + extension.toLowerCase());
                    candidates.add(name + extension.toUpperCase());
                }
            }
        } else {
            candidates.add(name + ".exe");
            candidates.add(name + ".cmd");
            candidates.add(name + ".bat");
        }
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(
            File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            for (String candidate : candidates) {
                File file = new File(entry, candidate);
                if (file.isFile() && file.canExecute()) {
                    return true;
                }
            }
        }
        return false;
    }
}
