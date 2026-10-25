package cz.cuni.mff.releasemanager;

import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.types.RepoRef;


class ReleaseManagerRollbackTest extends ReleaseManagerTestSupport {

    @Test
    void testExecuteRollbackOnWindowsReinstallsBackupInstaller() throws Exception {
        setOs(Platform.OS.WINDOWS);
        Command cmd = Command.ROLLBACK;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest current = withProductCode(
            manifest("user/example", "http://v2", "app.msi", "/cache/v2.msi"),
            "{22222222-2222-2222-2222-222222222222}");
        AppManifest restored = withProductCode(
            manifest("user/example", "http://v1", "app.msi", "/cache/v1.msi"),
            "{11111111-1111-1111-1111-111111111111}");
        AppManifest.Backup backup = backup("0.9.0");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(current));
        when(mockPlatformHandler.supportsInstallerRollback(current)).thenReturn(true);
        when(mockBackupManager.latest(current)).thenReturn(Optional.of(backup));
        when(mockBackupManager.readManifest(backup)).thenReturn(restored);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);
        when(mockPlatformHandler.rollbackInstaller(
            current, restored, Path.of(backup.artifact()))).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"rollback", "github:user/example"}));

        verify(mockPlatformHandler).rollbackInstaller(
            current, restored, Path.of(backup.artifact()));
        verify(mockBackupManager, never()).restore(any(), any());
        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("1.0.0", captor.getValue().version());
        assertEquals("{11111111-1111-1111-1111-111111111111}",
            captor.getValue().install().uninstall().productCode());
    }

    @Test
    void testExecuteRollbackOnWindowsRequiresMsiInstaller() throws Exception {
        setOs(Platform.OS.WINDOWS);
        Command cmd = Command.ROLLBACK;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest current = manifest(
            "user/example", "http://v2", "app.exe", "/ProgramFiles/app/uninst.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(current));
        when(mockPlatformHandler.supportsInstallerRollback(current)).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"rollback", "github:user/example"}));

        verify(mockBackupManager, never()).latest(any());
        verify(mockPlatformHandler, never()).rollbackInstaller(any(), any(), any());
    }

    @Test
    void testExecuteRollbackOnWindowsStopsWhenInstallerRollbackFails() throws Exception {
        setOs(Platform.OS.WINDOWS);
        Command cmd = Command.ROLLBACK;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest current = manifest(
            "user/example", "http://v2", "app.msi", "/cache/v2.msi");
        AppManifest restored = manifest(
            "user/example", "http://v1", "app.msi", "/cache/v1.msi");
        AppManifest.Backup backup = backup("0.9.0");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(current));
        when(mockPlatformHandler.supportsInstallerRollback(current)).thenReturn(true);
        when(mockBackupManager.latest(current)).thenReturn(Optional.of(backup));
        when(mockBackupManager.readManifest(backup)).thenReturn(restored);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);
        when(mockPlatformHandler.rollbackInstaller(
            current, restored, Path.of(backup.artifact()))).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"rollback", "github:user/example"}));

        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteRollbackJson() throws Exception {
        Command cmd = Command.ROLLBACK;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        AppManifest current = manifest(
            "user/example", "http://new", "example.exe", "/tmp/live");
        AppManifest.Backup backup = backup("0.9.0");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(current));
        when(mockBackupManager.latest(current)).thenReturn(Optional.of(backup));
        when(mockBackupManager.readManifest(backup)).thenReturn(current);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"rollback", "github:user/example", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals("user/example", root.get("rolled_back").get("repo").asText());
        assertEquals("0.9.0", root.get("rolled_back").get("version").asText());
    }

    @Test
    void testExecuteRollbackDryRunStopsBeforeRestore() throws Exception {
        Command cmd = Command.ROLLBACK;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isDryRun()).thenReturn(true);
        AppManifest current = manifest(
            "user/example", "http://new", "example.exe", "/tmp/live");
        AppManifest.Backup backup = backup("0.9.0");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(current));
        when(mockBackupManager.latest(current)).thenReturn(Optional.of(backup));

        assertEquals(0, releaseManager.execute(new String[]{"rollback", "github:user/example"}));

        verify(mockBackupManager, never()).restore(any(), any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteRollbackRestoresLatestBackup() throws Exception {
        Command cmd = Command.ROLLBACK;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest current = manifest(
            "user/example", "http://new", "example.exe", "/tmp/live");
        AppManifest restored = manifest(
            "user/example", "http://old", "example.exe", "/tmp/live");
        AppManifest.Backup backup = backup("0.9.0");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(current));
        when(mockBackupManager.latest(current)).thenReturn(Optional.of(backup));
        when(mockBackupManager.readManifest(backup)).thenReturn(restored);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"rollback", "github:user/example"}));

        verify(mockBackupManager).restore(backup, Path.of("/tmp/live"));
        verify(mockStateStore).saveManifest(any());
        verify(mockStateStore).rebuildIndex();
    }

    @Test
    void testExecuteRollbackRefusesWithoutBackups() throws Exception {
        Command cmd = Command.ROLLBACK;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest current = manifest(
            "user/example", "http://new", "example.exe", "/tmp/live");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(current));
        when(mockBackupManager.latest(current)).thenReturn(Optional.empty());

        assertEquals(1, releaseManager.execute(new String[]{"rollback", "github:user/example"}));

        verify(mockBackupManager, never()).restore(any(), any());
    }

    @Test
    void testExecuteRollbackCanBeDeclined() throws Exception {
        Command cmd = Command.ROLLBACK;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest current = manifest(
            "user/example", "http://new", "example.exe", "/tmp/live");
        AppManifest.Backup backup = backup("0.9.0");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(current));
        when(mockBackupManager.latest(current)).thenReturn(Optional.of(backup));
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"rollback", "github:user/example"}));

        verify(mockBackupManager, never()).restore(any(), any());
        verify(mockStateStore, never()).saveManifest(any());
    }
}
