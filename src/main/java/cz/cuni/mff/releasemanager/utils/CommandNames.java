package cz.cuni.mff.releasemanager.utils;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;

// picks the terminal command name for a portable package
public final class CommandNames {

    private static final Pattern VALID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._+-]*");
    private static final Pattern VERSION = Pattern.compile("v?\\d+(\\.\\d+)*");
    private static final Set<String> SUFFIXES = Set.of(
        "linux", "windows", "win", "win32", "win64", "macos", "mac", "darwin", "osx",
        "freebsd", "openbsd", "netbsd",
        "x86", "x86_64", "x86-64", "amd64", "x64", "i386", "i686",
        "arm", "arm64", "aarch64", "armv7", "armv8",
        "universal", "universal2", "fat", "static", "musl", "gnu",
        "portable", "standalone", "setup", "installer", "install", "cli", "gui");

    private CommandNames() {}

    public static String fromAsset(RepoRef ref, Asset asset) {
        String derived = fromAssetName(asset == null ? null : asset.name());
        if (derived == null || derived.isBlank()) {
            derived = repoName(ref);
        }
        return validate(derived.toLowerCase(Locale.ROOT));
    }

    public static String fromAssetName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String stem = fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            String extension = stem.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (Set.of("appimage", "ape", "com", "exe", "msi", "dmg", "bin")
                .contains(extension)) {
                stem = stem.substring(0, dot);
            }
        }
        String[] tokens = stem.split("[._-]+");
        StringBuilder name = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (isSuffix(token)) {
                break;
            }
            if (name.length() > 0) {
                name.append('-');
            }
            name.append(token);
        }
        return name.isEmpty() ? null : name.toString().toLowerCase(Locale.ROOT);
    }

    public static String validate(String value) {
        if (value == null || !VALID.matcher(value).matches()
            || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("Invalid command name: " + value);
        }
        return value;
    }

    private static boolean isSuffix(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return VERSION.matcher(lower).matches() || SUFFIXES.contains(lower);
    }

    private static String repoName(RepoRef ref) {
        if (ref == null || ref.path() == null) {
            return "app";
        }
        String[] parts = ref.path().split("/");
        String name = parts[parts.length - 1];
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }
}
