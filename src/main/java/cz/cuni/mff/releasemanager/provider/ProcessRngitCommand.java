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

    @Override
    public Result run(List<String> args, Path directory, Duration timeout)
        throws IOException, InterruptedException {
        if (!Executables.isAvailable("rngit")) {
            throw new ProviderException(ProviderException.Reason.UNREACHABLE,
                "rngit is not installed; install RNS with 'pip install rns'.");
        }
        ProcessBuilder builder = new ProcessBuilder();
        builder.command("rngit");
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
