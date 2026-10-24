package cz.cuni.mff.releasemanager.state;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.cuni.mff.releasemanager.platform.Platform;

class AppPathsTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsConfigIndexAndApplicationPaths() {
        AppPaths paths = new AppPaths(tempDir);

        assertEquals(tempDir.toAbsolutePath().normalize(), paths.root());
        assertEquals(paths.root().resolve("grm_config.json"), paths.configFile());
        assertEquals(paths.root().resolve("grm_index.json"), paths.indexFile());
        assertEquals(paths.root().resolve("grm_lock"), paths.lockFile());
        assertEquals(
            paths.root().resolve("applications").resolve("github").resolve("owner__repo"),
            paths.appDir("github", "owner/repo"));
        assertEquals(
            paths.appDir("github", "owner/repo").resolve("grm_manifest.json"),
            paths.manifestFile("github", "owner/repo"));
    }

    @Test
    void normalizesRepositoryDirectoryNames() {
        assertEquals("keepassxreboot__keepassxc",
            AppPaths.repoDirectoryName("KeePassXReboot/KeePassXC"));
        assertEquals("owner__repo-name", AppPaths.repoDirectoryName("owner/repo name"));
    }

    @Test
    void rejectsInvalidRepositoryNames() {
        assertThrows(IllegalArgumentException.class,
            () -> AppPaths.repoDirectoryName("owner"));
    }

    @Test
    void usesLocalShareOnLinux() {
        AppPaths paths = AppPaths.forPlatform(
            Platform.OS.LINUX, "/home/me", "/local");

        assertEquals(Path.of("/home/me", ".local", "share", AppPaths.APP_DIR)
            .toAbsolutePath().normalize(), paths.root());
    }

    @Test
    void usesApplicationSupportOnMac() {
        AppPaths paths = AppPaths.forPlatform(
            Platform.OS.MAC, "/Users/me", "/local");

        assertEquals(Path.of("/Users/me", "Library", "Application Support", AppPaths.APP_DIR)
            .toAbsolutePath().normalize(), paths.root());
    }

    @Test
    void usesLocalAppDataOnWindows() {
        AppPaths paths = AppPaths.forPlatform(
            Platform.OS.WINDOWS, "/home/me", "/local");

        assertEquals(Path.of("/local", AppPaths.APP_DIR).toAbsolutePath().normalize(),
            paths.root());
    }
}
