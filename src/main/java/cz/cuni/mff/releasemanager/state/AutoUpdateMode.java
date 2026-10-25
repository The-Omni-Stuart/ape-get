package cz.cuni.mff.releasemanager.state;

import java.util.Locale;

// whether installed packages are checked or updated automatically
public enum AutoUpdateMode {
    OFF("off"),
    CHECK("check"),
    APPLY("apply");

    private final String id;

    AutoUpdateMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean enabled() {
        return this != OFF;
    }

    public static AutoUpdateMode from(String value) {
        if (value == null || value.isBlank() || "off".equalsIgnoreCase(value)) {
            return OFF;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "check" -> CHECK;
            case "apply" -> APPLY;
            default -> OFF;
        };
    }
}
