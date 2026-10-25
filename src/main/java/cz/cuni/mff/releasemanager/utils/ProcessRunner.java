package cz.cuni.mff.releasemanager.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

// runs a child process with a hard timeout and captures stdout and stderr
public final class ProcessRunner {

    public record Result(boolean timedOut, int exitCode, String output) {}

    private ProcessRunner() {}

    public static Result run(ProcessBuilder builder, Duration timeout)
        throws IOException, InterruptedException {
        Path log = Files.createTempFile("grm-process-", ".log");
        Process process = null;
        try {
            process = builder
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start();
            process.getOutputStream().close(); // stdin EOF, so a licence prompt fails instead of hanging
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new Result(true, -1, read(log));
            }
            return new Result(false, process.exitValue(), read(log));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                Files.deleteIfExists(log);
            } catch (IOException ignored) {
                // the temp log is best-effort cleanup
            }
        }
    }

    private static String read(Path log) {
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
