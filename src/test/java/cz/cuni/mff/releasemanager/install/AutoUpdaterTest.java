package cz.cuni.mff.releasemanager.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.state.UpdateLog;

class AutoUpdaterTest {

    @TempDir
    Path tempDir;

    @Test
    void logsEachFailedPackage() throws Exception {
        GrmConfig config = new GrmConfig(
            GrmConfig.KIND, GrmConfig.SCHEMA_VERSION,
            false, 1, null, false, Map.of(), true,
            "stable", "apply", 24, 30, null);
        StateStore stateStore = mock(StateStore.class);
        CmdParser cmdParser = mock(CmdParser.class);
        when(cmdParser.getChannel()).thenReturn(null);
        UpdateChecker checker = mock(UpdateChecker.class);
        InstallService installService = mock(InstallService.class);
        when(installService.updateAllBatch(false, false)).thenReturn(
            new InstallService.Batch(false, List.of(new UpdateResult(
                "github", "user/example", "1.0.0", null,
                UpdateResult.Status.FAILED, "checksum mismatch"))));
        Path logFile = tempDir.resolve("grm_updates.log");

        new AutoUpdater(config, stateStore, cmdParser, checker,
            installService, new UpdateLog(logFile)).maybeRun();

        String log = Files.readString(logFile);
        assertTrue(log.contains("github:user/example"), log);
        assertTrue(log.contains("checksum mismatch"), log);
    }
}
