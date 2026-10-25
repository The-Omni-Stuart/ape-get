package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

// seam so provider parsing and downloads can be tested without a live RNS node
interface RngitCommand {
    Result run(List<String> args, Path directory, Duration timeout)
        throws IOException, InterruptedException;

    record Result(int exitCode, String output) {}
}
