package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.state.AppPaths;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformPathsTest {

    @Test
    void usesLocalShareOnLinux() {
        AppPaths paths = PlatformPaths.forPlatform(
            Platform.OS.LINUX, "/home/me", "/local");

        assertEquals(Path.of("/home/me", ".local", "share", AppPaths.APP_DIR)
            .toAbsolutePath().normalize(), paths.root());
    }

    @Test
    void usesApplicationSupportOnMac() {
        AppPaths paths = PlatformPaths.forPlatform(
            Platform.OS.MAC, "/Users/me", "/local");

        assertEquals(Path.of("/Users/me", "Library", "Application Support", AppPaths.APP_DIR)
            .toAbsolutePath().normalize(), paths.root());
    }

    @Test
    void usesLocalAppDataOnWindows() {
        AppPaths paths = PlatformPaths.forPlatform(
            Platform.OS.WINDOWS, "/home/me", "/local");

        assertEquals(Path.of("/local", AppPaths.APP_DIR).toAbsolutePath().normalize(),
            paths.root());
    }
}
