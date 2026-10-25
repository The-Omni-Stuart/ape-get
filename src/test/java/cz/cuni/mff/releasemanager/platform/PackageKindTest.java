package cz.cuni.mff.releasemanager.platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageKindTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesKindOptions() {
        assertEquals(PackageKind.APPIMAGE, PackageKind.fromOption("appimage"));
        assertEquals(PackageKind.PORTABLE_EXE, PackageKind.fromOption("portable"));
        assertEquals(PackageKind.APE, PackageKind.fromOption("ape"));
        assertEquals(PackageKind.NSIS, PackageKind.fromOption("installer"));
        assertNull(PackageKind.fromOption("auto"));
    }

    @Test
    void keepsOldExeManifestMeaning() {
        assertEquals(PackageKind.NSIS, PackageKind.fromManifest("exe"));
        assertEquals(PackageKind.PORTABLE_EXE, PackageKind.fromManifest("portable-exe"));
        assertEquals("exe", PackageKind.NSIS.manifestName());
    }

    @Test
    void classifiesAssetNames() {
        assertEquals(PackageKind.APPIMAGE,
            PackageKind.fromAssetName("tool-x86_64.AppImage"));
        assertEquals(PackageKind.APE, PackageKind.fromAssetName("tool.com"));
        assertEquals(PackageKind.NSIS, PackageKind.fromAssetName("tool.exe"));
        assertEquals(PackageKind.DMG, PackageKind.fromAssetName("tool.dmg"));
        assertEquals(PackageKind.UNKNOWN, PackageKind.fromAssetName("tool"));
    }

    @Test
    void detectsApeMagic() throws Exception {
        Path ape = tempDir.resolve("tool");
        Files.writeString(ape, "MZqFpD='rest", StandardCharsets.US_ASCII);
        Path sh = tempDir.resolve("tool.com");
        Files.writeString(sh, "jartsr='rest", StandardCharsets.US_ASCII);
        Path plain = tempDir.resolve("plain");
        Files.writeString(plain, "hello", StandardCharsets.US_ASCII);

        assertTrue(PackageKind.hasApeMagic(ape));
        assertTrue(PackageKind.hasApeMagic(sh));
        assertFalse(PackageKind.hasApeMagic(plain));
    }

    @Test
    void checksPlatformSupport() {
        assertTrue(PackageKind.APPIMAGE.supportsOn(Platform.OS.LINUX));
        assertFalse(PackageKind.APPIMAGE.supportsOn(Platform.OS.WINDOWS));
        assertTrue(PackageKind.APE.supportsOn(Platform.OS.MAC));
        assertTrue(PackageKind.PORTABLE_EXE.supportsOn(Platform.OS.WINDOWS));
    }
}
