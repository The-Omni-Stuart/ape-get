package cz.cuni.mff.releasemanager.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

import cz.cuni.mff.releasemanager.utils.Output;

// append-only record of automatic update checks and actions
public final class UpdateLog {
    private final Path file;

    public UpdateLog(Path file) {
        this.file = file;
    }

    public boolean due(int intervalHours) {
        if (!Files.isRegularFile(file)) {
            return true;
        }
        try {
            Instant last = Files.getLastModifiedTime(file).toInstant();
            return last.plus(Duration.ofHours(Math.max(1, intervalHours)))
                .isBefore(Instant.now());
        } catch (IOException e) {
            return true;
        }
    }

    public void append(String message) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now() + " " + message + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Output.error("Failed to write update log: " + e.getMessage());
        }
    }
}
