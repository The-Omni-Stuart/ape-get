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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.InstallRequest;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.verify.VerificationResult;


class ReleaseManagerUpdateTest extends ReleaseManagerTestSupport {

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

        verify(mockGithubClient).discardAsset(downloaded);
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
}
