package cz.cuni.mff.releasemanager.state;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

// state directory layout
public record AppPaths(Path root) {
    public static final String APP_DIR = "github-release-manager";

    public AppPaths {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path configFile() {
        return root.resolve("grm_config.json");
    }

    public Path indexFile() {
        return root.resolve("grm_index.json");
    }

    public Path lockFile() {
        return root.resolve("grm_lock");
    }

    public Path updateLogFile() {
        return root.resolve("grm_updates.log");
    }

    public Path applicationsDir() {
        return root.resolve("applications");
    }

    public Path appDir(String provider, String repoFullName) {
        return applicationsDir()
            .resolve(sanitize(provider))
            .resolve(repoDirectoryName(repoFullName));
    }

    public Path manifestFile(String provider, String repoFullName) {
        return appDir(provider, repoFullName).resolve("grm_manifest.json");
    }

    // owner/repo -> owner__repo, with a hash when the name itself contains "__"
    public static String repoDirectoryName(String repoFullName) {
        if (repoFullName == null) {
            throw new IllegalArgumentException("Repository name is required");
        }
        String[] segments = repoFullName.split("/", -1);
        if (segments.length < 2 || repoFullName.contains("\\")) {
            throw new IllegalArgumentException("Expected owner/repo: " + repoFullName);
        }
        StringBuilder name = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank()) {
                throw new IllegalArgumentException("Expected owner/repo: " + repoFullName);
            }
            if (name.length() > 0) {
                name.append("__");
            }
            name.append(sanitize(segment));
        }
        // "a/b__c" and "a__b/c" both join to a__b__c, so disambiguate the rare case
        if (repoFullName.contains("__")) {
            name.append("--").append(Integer.toUnsignedString(
                repoFullName.hashCode(), 16));
        }
        return name.toString();
    }

    private static String sanitize(String value) {
        String sanitized = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[._-]+|[._-]+$", "");
        return sanitized.isBlank() ? "app" : sanitized;
    }
}
