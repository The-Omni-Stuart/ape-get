package cz.cuni.mff.releasemanager.state;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateLogTest {

    @TempDir
    Path tempDir;

    @Test
    void absentLogIsDueThenAppendMakesItFresh() {
        Path file = tempDir.resolve("grm_updates.log");
        UpdateLog log = new UpdateLog(file);

        assertTrue(log.due(24));
        log.append("check: up to date");
        assertTrue(Files.isRegularFile(file));
        assertFalse(log.due(24));
    }
}
