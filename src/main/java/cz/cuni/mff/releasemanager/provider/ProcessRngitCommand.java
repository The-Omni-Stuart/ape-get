package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import cz.cuni.mff.releasemanager.utils.Executables;

// runs the rngit CLI; release fetch verifies the signed manifest itself
final class ProcessRngitCommand implements RngitCommand {
    // rngit creates its client identity/config on first use, so starts must not race
    private static final Object SERIAL = new Object();

    private final String executable;

    ProcessRngitCommand() {
        this("rngit");
    }

    ProcessRngitCommand(Path executable) {
        this(executable.toString());
    }

    private ProcessRngitCommand(String executable) {
        this.executable = executable;
    }

    @Override
    public Result run(List<String> args, Path directory, Duration timeout)
        throws IOException, InterruptedException {
        if ("rngit".equals(executable)) {
            if (!Executables.isAvailable("rngit")) {
                throw new ProviderException(ProviderException.Reason.UNREACHABLE,
                    "rngit is not installed; install RNS with 'pip install rns'.");
            }
        } else if (!Files.isExecutable(Path.of(executable))) {
            throw new ProviderException(ProviderException.Reason.UNREACHABLE,
                "rngit is not executable: " + executable);
        }
        synchronized (SERIAL) {
            return runSerial(args, directory, timeout);
        }
    }

    private Result runSerial(List<String> args, Path directory, Duration timeout)
        throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(executable);
        builder.command().addAll(args);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Path outputFile = Files.createTempFile("grm-rngit-output-", ".log");
        builder.redirectOutput(outputFile.toFile());
        long seconds = Math.max(1, timeout.toSeconds());
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ProviderException(ProviderException.Reason.TIMEOUT,
                    "rngit command timed out after " + seconds + "s");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            return new Result(process.exitValue(), output);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException ignored) {
                // the killed process may still hold the file on Windows
            }
        }
    }
}
