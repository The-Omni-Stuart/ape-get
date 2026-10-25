package cz.cuni.mff.releasemanager.utils;

import com.fasterxml.jackson.core.JsonProcessingException;

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
            System.err.println(message);
        }
    }

    // command data, always printed to stdout
    public static void data(String message) {
        System.out.println(message);
    }

    // warnings and errors, always stderr
    public static void error(String message) {
        System.err.println(message);
    }

    public static void errorf(String format, Object... args) {
        System.err.printf(format, args);
    }

    // the one JSON document a --json command writes to stdout
    public static void json(Object value) {
        try {
            data(Json.write(value));
        } catch (JsonProcessingException e) {
            error("Failed to render JSON: " + e.getMessage());
        }
    }

    // interactive prompt text, kept off stdout
    public static void prompt(String message) {
        System.err.print(message);
        System.err.flush();
    }
}
