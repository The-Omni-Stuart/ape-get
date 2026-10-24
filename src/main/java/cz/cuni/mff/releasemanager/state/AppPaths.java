package cz.cuni.mff.releasemanager.state;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

import cz.cuni.mff.releasemanager.platform.Platform;

/**
 * Platform-specific application data paths.
 */
public record AppPaths(Path root) {
    public static final String APP_DIR = "github-release-manager";

    public AppPaths {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /**
     * @return paths for the current platform
     */
    public static AppPaths forCurrentPlatform() {
        return forPlatform(
            Platform.detectPlatform().getOS(),
            System.getProperty("user.home"),
            System.getenv("LOCALAPPDATA"));
    }

    static AppPaths forPlatform(Platform.OS os, String userHome, String localAppData) {
        return switch (os) {
            case LINUX -> new AppPaths(
                Paths.get(userHome, ".local", "share", APP_DIR));
            case MAC -> new AppPaths(
                Paths.get(userHome, "Library", "Application Support", APP_DIR));
            case WINDOWS -> new AppPaths(
                Paths.get(
                    Objects.requireNonNull(localAppData, "LOCALAPPDATA is not set"),
                    APP_DIR));
        };
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

    public Path applicationsDir() {
        return root.resolve("applications");
    }

    public Path downloadsDir() {
        return root.resolve("downloads");
    }

    public Path appDir(String provider, String repoFullName) {
        return applicationsDir()
            .resolve(sanitize(provider))
            .resolve(repoDirectoryName(repoFullName));
    }

    public Path manifestFile(String provider, String repoFullName) {
        return appDir(provider, repoFullName).resolve("grm_manifest.json");
    }

    /**
     * Converts owner/repo into a filesystem-safe directory name.
     * @param repoFullName repository in owner/name form
     * @return sanitized owner__name
     */
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
        return name.toString();
    }

    private static String sanitize(String value) {
        String sanitized = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[._-]+|[._-]+$", "");
        return sanitized.isBlank() ? "app" : sanitized;
    }
}
