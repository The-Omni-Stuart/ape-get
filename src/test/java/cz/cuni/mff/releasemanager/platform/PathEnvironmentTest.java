package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathEnvironmentTest {

    @Test
    void findsExactDirectoryEntry() {
        assertTrue(PathEnvironment.contains(
            "/usr/bin:/home/user/.local/bin", Path.of("/home/user/.local/bin"), ":"));
    }

    @Test
    void ignoresTrailingSeparatorAndRelativeComponents() {
        assertTrue(PathEnvironment.contains(
            "/home/user/.local/bin/:/usr/bin", Path.of("/home/user/.local/bin"), ":"));
        assertFalse(PathEnvironment.contains(
            "./bin", Path.of("/home/user/.local/bin"), ":"));
    }

    @Test
    void rejectsMissingOrParentDirectories() {
        assertFalse(PathEnvironment.contains(
            "/usr/bin", Path.of("/home/user/.local/bin"), ":"));
        assertFalse(PathEnvironment.contains(
            "/usr/bin:", Path.of("/home/user/.local/bin"), ":"));
        assertFalse(PathEnvironment.contains(null, Path.of("/home/user/.local/bin"), ":"));
    }
}
