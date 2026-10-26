package cz.cuni.mff.releasemanager.utils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Removes provider token variables from processes spawned by the tool.
 */
public final class ChildEnvironment {
    private static final Set<String> TOKEN_VARIABLES = ConcurrentHashMap.newKeySet();
    private static final Set<String> RESERVED = Set.of(
        "PATH", "HOME", "TMPDIR", "LD_PRELOAD", "LD_LIBRARY_PATH");

    static {
        TOKEN_VARIABLES.add("GRM_GITHUB_TOKEN");
    }

    private ChildEnvironment() {}

    /**
     * Registers a token variable name that must not reach child processes.
     * @param name environment variable name
     */
    public static void register(String name) {
        if (name != null && !name.isBlank() && !isReserved(name)) {
            TOKEN_VARIABLES.add(name);
        }
    }

    /**
     * @param name environment variable name
     * @return true when the variable must never be treated as a provider token
     */
    public static boolean isReserved(String name) {
        return name != null && RESERVED.contains(name);
    }

    /**
     * Removes registered token variables from a process builder.
     * @param builder process builder
     * @return the same builder
     */
    public static ProcessBuilder sanitize(ProcessBuilder builder) {
        if (builder != null) {
            builder.environment().keySet().removeIf(TOKEN_VARIABLES::contains);
        }
        return builder;
    }

    static void resetForTests() {
        TOKEN_VARIABLES.clear();
        TOKEN_VARIABLES.add("GRM_GITHUB_TOKEN");
    }
}
