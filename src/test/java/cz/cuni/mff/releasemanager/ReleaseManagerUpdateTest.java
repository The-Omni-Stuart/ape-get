package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.InstallRequest;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.verify.VerificationResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleaseManagerUpdateTest extends ReleaseManagerTestSupport {

    @Test
    void testExecuteUpdateCanBeDeclined() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(false);

        AppManifest previous = manifest(
            "user/example", "http://old", "example.exe", "/ProgramFiles/uninst.exe");
        Asset newAsset = new Asset("http://newurl", "example.AppImage");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(previous));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(
                "1.1.0", 2L, List.of(newAsset), List.of(newAsset)));

        int[] code = new int[1];
        String error = captureErr(() -> code[0] = releaseManager.execute(
            new String[]{"update", "github:user/example"}));

        assertEquals(1, code[0]);
        assertTrue(error.contains("Update declined"), error);
        verify(mockGithubClient, never()).downloadAsset(any());
        verify(mockPlatformHandler, never()).install(any());
    }

    @Test
    void testExecuteUpdateAllCanBeDeclined() throws Exception {
        Command cmd = Command.UPDATE;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isAll()).thenReturn(true);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(false);
        when(mockStateStore.scanManifests()).thenReturn(List.of(manifest(
            "user/example", "http://old", "example.exe", "/ProgramFiles/uninst.exe")));

        int[] code = new int[1];
        String error = captureErr(() -> code[0] = releaseManager.execute(
            new String[]{"update", "--all"}));

        assertEquals(1, code[0]);
        assertTrue(error.contains("Update declined"), error);
        verify(mockGithubClient, never()).getLatestRelease(any());
    }

    @Test
    void testExecuteUpdateSkipsBackupForWindowsNsis() throws Exception {
        setOs(Platform.OS.WINDOWS);
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest previous = manifest(
            "user/example", "http://old", "example.exe", "/ProgramFiles/uninst.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(previous));
        when(mockPlatformHandler.supportsInstallerRollback(previous)).thenReturn(false);
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockPlatformHandler.install(any(InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/uninst.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockBackupManager, never()).createAndPrune(any(), anyInt());
    }

    @Test
    void testExecuteUpdateCreatesBackupForWindowsMsi() throws Exception {
        setOs(Platform.OS.WINDOWS);
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest previous = manifest(
            "user/example", "http://old", "example.msi", "/cache/v1.msi");
        Asset newAsset = new Asset("http://newurl", "example.msi");
        Path downloaded = Path.of("/tmp/downloaded.msi");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(previous));
        when(mockPlatformHandler.supportsInstallerRollback(previous)).thenReturn(true);
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockPlatformHandler.install(any(InstallRequest.class)))
            .thenReturn(Path.of("/cache/v2.msi"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockBackupManager).createAndPrune(any(), anyInt());
    }

    @Test
    void testExecuteUpdateJson() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        Path installed = Path.of("/ProgramFiles/example.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), installed.toString())));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(
                "2.0.0", 2L, List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockPlatformHandler.install(any(InstallRequest.class))).thenReturn(installed);

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"update", "github:user/example", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals("2.0.0", root.get("updated").get("version").asText());
        assertEquals(installed.toString(), root.get("updated").get("path").asText());
    }

    @Test
    void testExecuteUpdateDryRunStopsBeforeDownload() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isDryRun()).thenReturn(true);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));

        assertEquals(0, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockGithubClient, never()).downloadAsset(any());
        verify(mockBackupManager, never()).createAndPrune(any(), anyInt());
        verify(mockPlatformHandler, never()).install(any(InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdate() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockGithubClient).downloadAsset(newAsset);
        verify(mockPlatformHandler).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockBackupManager).createAndPrune(any(), anyInt());
        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        verify(mockStateStore).rebuildIndex();
        assertEquals("user/example", captor.getValue().repo());
        assertEquals("http://newurl", captor.getValue().asset().url());
        assertEquals("FPR", captor.getValue().signature().identity());
    }

    @Test
    void testExecuteUpdateInstallsWithoutUninstallingOld() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "github:user/example"}));

        InOrder inOrder = inOrder(mockGithubClient, mockVerifier, mockPlatformHandler);
        inOrder.verify(mockGithubClient).downloadAsset(newAsset);
        inOrder.verify(mockVerifier).verify(any(), any(), any(), any());
        inOrder.verify(mockPlatformHandler).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockStateStore).saveManifest(any());
    }

    @Test
    void testExecuteUpdateInstallFailureReturnsError() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class))).thenReturn(null);

        assertEquals(1, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockPlatformHandler).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateFailsOnChecksumMismatch() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.mismatch(
                newAsset.name(), "expected", "actual", "checksum"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "github:user/example"}));
        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateDoesNotUninstallWhenDownloadFails() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenThrow(new IOException("boom"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }


    @Test
    void testExecuteUpdateAllJsonProducesOneBatch() throws Exception {
        Command cmd = Command.UPDATE;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isAll()).thenReturn(true);
        when(mockCmdParser.isJson()).thenReturn(true);

        AppManifest one = manifest(
            "user/one", "http://one/old", "example.exe", "/ProgramFiles/one-uninst.exe");
        AppManifest two = manifest(
            "user/two", "http://two/old", "example.exe", "/ProgramFiles/two-uninst.exe");
        when(mockStateStore.scanManifests()).thenReturn(List.of(one, two));
        when(mockStateStore.findManifest(new RepoRef("github", "user/one")))
            .thenReturn(Optional.of(one));
        when(mockStateStore.findManifest(new RepoRef("github", "user/two")))
            .thenReturn(Optional.of(two));

        Asset oneAsset = new Asset("http://one/new", "example.exe");
        Asset twoAsset = new Asset("http://two/new", "example.exe");
        when(mockGithubClient.getLatestRelease("user/one"))
            .thenReturn(new ReleaseAssets("2.0.0", 2L, List.of(oneAsset), List.of(oneAsset)));
        when(mockGithubClient.getLatestRelease("user/two"))
            .thenReturn(new ReleaseAssets("2.0.0", 2L, List.of(twoAsset), List.of(twoAsset)));
        when(mockGithubClient.downloadAsset(oneAsset))
            .thenReturn(Path.of("/tmp/one.exe"));
        when(mockGithubClient.downloadAsset(twoAsset))
            .thenReturn(Path.of("/tmp/two.exe"));
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified("example.exe", "checksum"));
        when(mockPlatformHandler.install(any(InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"update", "--all", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals(2, root.get("updates").size());
        assertEquals("updated", root.get("updates").get(0).get("status").asText());
        assertEquals("updated", root.get("updates").get(1).get("status").asText());
    }

    @Test
    void testExecuteUpdateAlreadyUpToDate() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", asset.url(), asset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));

        assertEquals(0, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockGithubClient, never()).downloadAsset(any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateAlreadyUpToDateJson() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);

        Asset asset = new Asset("http://url", "example.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", asset.url(), asset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));

        String output = captureOut(
            () -> releaseManager.execute(
                new String[]{"update", "github:user/example", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals("github", root.get("up_to_date").get("provider").asText());
        assertEquals("user/example", root.get("up_to_date").get("repo").asText());
        assertEquals("1.0.0", root.get("up_to_date").get("version").asText());
        verify(mockGithubClient, never()).downloadAsset(any());
    }
}
