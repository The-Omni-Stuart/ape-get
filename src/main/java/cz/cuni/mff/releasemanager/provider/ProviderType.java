package cz.cuni.mff.releasemanager.provider;

import java.util.Locale;
import java.util.Optional;

/**
 * Supported provider API families.
 */
public enum ProviderType {
    GITHUB("github"),
    GITEA("gitea"),
    GITLAB("gitlab");

    private final String id;

    ProviderType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<ProviderType> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (ProviderType type : values()) {
            if (type.id.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
