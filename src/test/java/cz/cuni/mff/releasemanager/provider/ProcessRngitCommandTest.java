package cz.cuni.mff.releasemanager.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ProcessRngitCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(10)
    void serializesRngitProcesses() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path log = tempDir.resolve("log");
        Path script = tempDir.resolve("rngit");
        Files.writeString(script, "#!/bin/sh\n"
            + "echo start >> '" + log + "'\n"
            + "sleep 0.3\n"
            + "echo end >> '" + log + "'\n");
        Files.setPosixFilePermissions(
            script, PosixFilePermissions.fromString("rwx------"));
        ProcessRngitCommand command = new ProcessRngitCommand(script);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RngitCommand.Result> first = executor.submit(
                () -> command.run(List.of(), tempDir, Duration.ofSeconds(5)));
            Future<RngitCommand.Result> second = executor.submit(
                () -> command.run(List.of(), tempDir, Duration.ofSeconds(5)));

            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(List.of("start", "end", "start", "end"),
            Files.readAllLines(log));
    }
}
