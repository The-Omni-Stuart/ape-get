package cz.cuni.mff.releasemanager.verify;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses checksum files in the common sha256sum and BSD formats.
 */
public final class ChecksumFile {
    private static final Pattern BSD = Pattern.compile(
        "^SHA256\\s*\\((.+)\\)\\s*=\\s*([0-9a-fA-F]{64})$", Pattern.CASE_INSENSITIVE);

    private ChecksumFile() {}

    /**
     * @param hasEntries true if at least one valid checksum line was found
     * @param ambiguous true if the target appeared with conflicting hashes
     * @param sha256 the matching hash, if unambiguous and present
     */
    public record Lookup(boolean hasEntries, boolean ambiguous, Optional<String> sha256) {}

    /**
     * Finds the SHA-256 for a filename in checksum-file content.
     * @param content file content
     * @param filename target asset name
     * @return lookup result
     */
    public static Lookup findSha256(String content, String filename) {
        if (content == null || filename == null) {
            return new Lookup(false, false, Optional.empty());
        }
        String target = normalizeName(filename);
        boolean hasEntries = false;
        String found = null;

        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String hash = null;
            String name = null;

            Matcher bsd = BSD.matcher(trimmed);
            if (bsd.matches()) {
                name = bsd.group(1);
                hash = bsd.group(2);
            } else {
                String[] parts = trimmed.split("\\s+", 2);
                if (parts.length == 1 && isSha256(parts[0])) {
                    hash = parts[0];
                    name = target;
                } else if (parts.length == 2 && isSha256(parts[0])) {
                    hash = parts[0];
                    name = parts[1].trim();
                    if (name.startsWith("*")) {
                        name = name.substring(1);
                    }
                }
            }

            if (hash == null || name == null) {
                continue;
            }
            hasEntries = true;
            if (!normalizeName(name).equalsIgnoreCase(target)) {
                continue;
            }
            String normalizedHash = hash.toLowerCase(Locale.ROOT);
            if (found != null && !found.equals(normalizedHash)) {
                return new Lookup(true, true, Optional.empty());
            }
            found = normalizedHash;
        }

        return new Lookup(hasEntries, false, Optional.ofNullable(found));
    }

    private static boolean isSha256(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeName(String name) {
        String normalized = name.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
