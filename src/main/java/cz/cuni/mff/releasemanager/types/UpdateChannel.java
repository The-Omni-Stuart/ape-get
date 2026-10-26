package cz.cuni.mff.releasemanager.types;

import java.util.Locale;

// which release stream an update should follow
public enum UpdateChannel {
    STABLE("stable"),
    BETA("beta");

    private final String id;

    UpdateChannel(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    // strict parser for CLI flags and config validation
    public static UpdateChannel parse(String value) {
        if (value == null || value.isBlank()) {
            return STABLE;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "stable" -> STABLE;
            case "beta" -> BETA;
            default -> throw new IllegalArgumentException("Unknown channel: " + value);
        };
    }

    public static UpdateChannel from(String value) {
        if (value == null || value.isBlank() || "stable".equalsIgnoreCase(value)) {
            return STABLE;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "beta" -> BETA;
            default -> STABLE;
        };
    }
}
