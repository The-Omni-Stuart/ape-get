package cz.cuni.mff.releasemanager.utils;

/**
 * Controls command output so {@code --quiet} and {@code --json} stay machine-readable.
 */
public final class Output {
    private static boolean quiet;
    private static boolean json;

    private Output() {}

    /**
     * @param quietMode suppress progress output
     * @param jsonMode emit only machine-readable data
     */
    public static void configure(boolean quietMode, boolean jsonMode) {
        quiet = quietMode;
        json = jsonMode;
    }

    /**
     * @param message progress message
     */
    public static void info(String message) {
        if (!quiet && !json) {
            System.out.println(message);
        }
    }

    /**
     * @param message command data
     */
    public static void data(String message) {
        System.out.println(message);
    }

    /**
     * @param message warning or error
     */
    public static void warn(String message) {
        System.err.println(message);
    }
}
