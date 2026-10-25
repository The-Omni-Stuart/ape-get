package cz.cuni.mff.releasemanager.types;

import java.util.regex.Pattern;

/**
 * A repository path on a configured provider: {@code provider:owner/repo}.
 */
public record RepoRef(String provider, String path) {
    private static final Pattern PROVIDER_NAME =
        Pattern.compile("[a-z0-9][a-z0-9._-]*");

    public RepoRef {
        if (provider == null || !PROVIDER_NAME.matcher(provider).matches()) {
            throw new IllegalArgumentException("Invalid provider name: " + provider);
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Repository path is required");
        }
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")
            || path.contains("\\") || path.contains(":")) {
            throw new IllegalArgumentException("Invalid repository path: " + path);
        }
        String[] segments = path.split("/", -1);
        if (segments.length < 2) {
            throw new IllegalArgumentException(
                "Expected provider:owner/repo: " + path);
        }
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Invalid repository path: " + path);
            }
        }
    }

    /**
     * Parses {@code provider:owner/repo}. The provider is required.
     * @param value command argument
     * @return parsed reference
     * @throws IllegalArgumentException if the value is malformed or has no provider
     */
    public static RepoRef parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Repository name is required");
        }
        int colon = value.indexOf(':');
        int slash = value.indexOf('/');
        if (colon <= 0 || (slash >= 0 && colon > slash)) {
            throw new IllegalArgumentException(
                "Provider is required; use provider:owner/repo, for example github:owner/repo");
        }
        return new RepoRef(
            value.substring(0, colon).toLowerCase(java.util.Locale.ROOT),
            value.substring(colon + 1));
    }

    public String repoName() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public String namespace() {
        return path.substring(0, path.lastIndexOf('/'));
    }

    public String display() {
        return provider + ":" + path;
    }

    @Override
    public String toString() {
        return display();
    }
}
