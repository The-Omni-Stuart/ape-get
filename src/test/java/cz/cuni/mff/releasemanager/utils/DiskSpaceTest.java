package cz.cuni.mff.releasemanager.utils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskSpaceTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsEnoughAndInsufficientSpace() {
        assertTrue(DiskSpace.hasAtLeast(tempDir, 1));
        assertFalse(DiskSpace.hasAtLeast(tempDir, Long.MAX_VALUE));
    }

    @Test
    void missingPathFallsBackToItsParent() {
        Path missing = tempDir.resolve("missing");
        assertFalse(Files.exists(missing));
        assertTrue(DiskSpace.usableSpace(missing) >= 0);
        assertFalse(DiskSpace.hasAtLeast(missing, Long.MAX_VALUE));
    }

    @Test
    void blankPathIsNotACheck() {
        assertTrue(DiskSpace.hasAtLeast(null, 1));
        assertTrue(DiskSpace.hasAtLeast(tempDir, 0));
    }
}
