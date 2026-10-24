package cz.cuni.mff.releasemanager.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateLockTest {

    @TempDir
    Path tempDir;

    @Test
    void createsLockFileAndAllowsReacquisition() throws Exception {
        Path lockFile = tempDir.resolve("grm_lock");

        try (StateLock ignored = StateLock.acquire(lockFile)) {
            assertTrue(Files.exists(lockFile));
        }

        try (StateLock ignored = StateLock.acquire(lockFile)) {
            assertTrue(Files.exists(lockFile));
        }
    }

    @Test
    void refusesSecondLockWhileHeld() throws Exception {
        Path lockFile = tempDir.resolve("grm_lock");

        try (StateLock ignored = StateLock.acquire(lockFile)) {
            assertThrows(IOException.class, () -> StateLock.acquire(lockFile));
        }
    }
}
