package cz.cuni.mff.releasemanager.types;

import java.util.Locale;
import java.util.Optional;

/**
 * Supported provider API families.
 */
public enum ProviderType {
    GITHUB("github"),
    GITEA("gitea"),
    GITLAB("gitlab"),
    RNGIT("rngit");

    private final String id;

    ProviderType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * @param url provider instance URL
     * @param api configured API base, or null to derive it
     * @return API base for this provider type
     */
    public String apiBase(String url, String api) {
        if (api != null && !api.isBlank()) {
            return stripTrailingSlash(api);
        }
        String base = stripTrailingSlash(url);
        return switch (this) {
            case GITHUB -> base.equals("https://github.com")
                || base.equals("http://github.com")
                ? "https://api.github.com" : base + "/api/v3";
            case GITEA -> base + "/api/v1";
            case GITLAB -> base + "/api/v4";
            case RNGIT -> base;
        };
    }

    /**
     * @param api API base
     * @return provider instance URL derived from the API base
     */
    public String urlFromApi(String api) {
        return switch (this) {
            case GITEA -> stripSuffix(api, "/api/v1");
            case GITLAB -> stripSuffix(api, "/api/v4");
            case RNGIT -> api;
            case GITHUB -> stripTrailingSlash(api).equals("https://api.github.com")
                ? "https://github.com"
                : stripSuffix(api, "/api/v3");
        };
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

    private static String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/")
            ? value.substring(0, value.length() - 1) : value;
    }

    private static String stripSuffix(String value, String suffix) {
        String base = stripTrailingSlash(value);
        return base.endsWith(suffix)
            ? base.substring(0, base.length() - suffix.length()) : base;
    }
}
