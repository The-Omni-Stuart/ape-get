package cz.cuni.mff.releasemanager.utils;

import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ProcessRunnerTest {

    @BeforeEach
    void requirePosixShell() {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
    }

    @Test
    @Timeout(10)
    void removesRegisteredTokensFromChildProcesses() throws Exception {
        ChildEnvironment.register("GRM_TEST_RUNNER_TOKEN");
        try {
            ProcessBuilder builder = new ProcessBuilder(
                "sh", "-c", "printf %s \"$GRM_TEST_RUNNER_TOKEN\"");
            builder.environment().put("GRM_TEST_RUNNER_TOKEN", "secret");

            ProcessRunner.Result result = ProcessRunner.run(
                builder, Duration.ofSeconds(5));

            assertEquals("", result.output().strip());
        } finally {
            ChildEnvironment.resetForTests();
        }
    }

    @Test
    @Timeout(10)
    void capturesOutputAndExitCode() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(
            new ProcessBuilder("sh", "-c", "echo hello; exit 0"), Duration.ofSeconds(5));

        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertEquals("hello", result.output().strip());
    }

    @Test
    @Timeout(10)
    void reportsNonZeroExitCode() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(
            new ProcessBuilder("sh", "-c", "echo bad; exit 3"), Duration.ofSeconds(5));

        assertEquals(3, result.exitCode());
        assertTrue(result.output().contains("bad"));
    }

    @Test
    @Timeout(10)
    void killsAChildThatNeverClosesStdout() throws Exception {
        long start = System.nanoTime();

        ProcessRunner.Result result = ProcessRunner.run(
            new ProcessBuilder("sh", "-c", "sleep 30"), Duration.ofMillis(300));

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.timedOut());
        assertTrue(elapsedMillis < 5_000, "waited " + elapsedMillis + " ms");
    }
}
