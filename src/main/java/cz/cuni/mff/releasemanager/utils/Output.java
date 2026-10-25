package cz.cuni.mff.releasemanager.utils;

// keeps --quiet and --json output machine-readable
public final class Output {
    private static boolean quiet;
    private static boolean json;

    private Output() {}

    // sets the mode once per command
    public static void configure(boolean quietMode, boolean jsonMode) {
        quiet = quietMode;
        json = jsonMode;
    }

    // progress output, hidden by --quiet and --json
    public static void info(String message) {
        if (!quiet && !json) {
            System.out.println(message);
        }
    }

    // command data, always printed
    public static void data(String message) {
        System.out.println(message);
    }
}
