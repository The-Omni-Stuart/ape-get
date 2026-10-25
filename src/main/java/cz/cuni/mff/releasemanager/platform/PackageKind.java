package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

// how an asset is installed, independent of the platform that installs it
public enum PackageKind {
    APPIMAGE("appimage"),
    PORTABLE_EXE("portable-exe"),
    APE("ape"),
    MSI("msi"),
    NSIS("exe"),
    DMG("dmg"),
    UNKNOWN("unknown");

    private static final byte[] APE_MZ =
        "MZqFpD='".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] APE_SH =
        "jartsr='".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final String manifestName;

    PackageKind(String manifestName) {
        this.manifestName = manifestName;
    }

    public String manifestName() {
        return manifestName;
    }

    public boolean isPortable() {
        return this == APPIMAGE || this == PORTABLE_EXE || this == APE;
    }

    public boolean supportsOn(Platform.OS os) {
        return switch (os) {
            case LINUX -> this == APPIMAGE || this == APE || this == UNKNOWN;
            case WINDOWS -> this == MSI || this == NSIS
                || this == PORTABLE_EXE || this == APE || this == UNKNOWN;
            case MAC -> this == DMG || this == APE || this == UNKNOWN;
        };
    }

    // user-facing --kind value; null means auto
    public static PackageKind fromOption(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value)) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "appimage" -> APPIMAGE;
            case "portable", "portable-exe", "portable_exe" -> PORTABLE_EXE;
            case "ape" -> APE;
            case "msi" -> MSI;
            case "exe", "nsis", "installer" -> NSIS;
            case "dmg" -> DMG;
            default -> throw new IllegalArgumentException("Unknown package kind: " + value);
        };
    }

    // old manifests stored NSIS as "exe"
    public static PackageKind fromManifest(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "appimage" -> APPIMAGE;
            case "portable", "portable-exe" -> PORTABLE_EXE;
            case "ape" -> APE;
            case "msi" -> MSI;
            case "exe", "nsis" -> NSIS;
            case "dmg" -> DMG;
            default -> UNKNOWN;
        };
    }

    // best guess from the asset name; .exe defaults to NSIS for compatibility
    public static PackageKind fromAssetName(String fileName) {
        if (fileName == null) {
            return UNKNOWN;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".appimage")) {
            return APPIMAGE;
        }
        if (name.endsWith(".msi")) {
            return MSI;
        }
        if (name.endsWith(".dmg")) {
            return DMG;
        }
        if (name.endsWith(".ape") || name.endsWith(".com")) {
            return APE;
        }
        if (name.endsWith(".exe")) {
            return NSIS;
        }
        return UNKNOWN;
    }

    // APE is magic, not extension; called after download
    public static boolean hasApeMagic(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        byte[] head = new byte[8];
        try (InputStream input = Files.newInputStream(file)) {
            int read = input.readNBytes(head, 0, head.length);
            if (read < head.length) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return java.util.Arrays.equals(head, APE_MZ)
            || java.util.Arrays.equals(head, APE_SH);
    }
}
