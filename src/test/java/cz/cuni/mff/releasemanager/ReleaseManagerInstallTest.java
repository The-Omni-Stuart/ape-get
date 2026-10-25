package cz.cuni.mff.releasemanager;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.InstallRequest;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.verify.VerificationResult;


class ReleaseManagerInstallTest extends ReleaseManagerTestSupport {

    @Test
    void testExecuteInstallUsesRequestedVersion() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getVersion()).thenReturn("v1.2.3");

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getRelease("user/example", "v1.2.3"))
            .thenReturn(new ReleaseAssets(
                "v1.2.3", 11L, List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "github:user/example"}));

        verify(mockGithubClient, never()).getLatestRelease(any());
        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("v1.2.3", captor.getValue().version());
        assertEquals(11L, captor.getValue().releaseId());
    }

    @Test
    void testExecuteInstallSelectsAssetByName() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getAsset()).thenReturn("app-x86_64.AppImage");

        Asset first = new Asset("http://url/one", "app-aarch64.AppImage");
        Asset chosen = new Asset("http://url/two", "app-x86_64.AppImage");
        Path downloaded = Path.of("/tmp/downloaded.AppImage");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(first, chosen), List.of(first, chosen)));
        when(mockGithubClient.downloadAsset(chosen)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(chosen.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/home/user/.local/bin/example"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "github:user/example"}));

        verify(mockGithubClient).downloadAsset(chosen);
        verify(mockGithubClient, never()).downloadAsset(first);
    }

    @Test
    void testExecuteInstallSelectsAssetByIndex() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getAsset()).thenReturn("2");

        Asset first = new Asset("http://url/one", "app-aarch64.AppImage");
        Asset second = new Asset("http://url/two", "app-x86_64.AppImage");
        Path downloaded = Path.of("/tmp/downloaded.AppImage");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(first, second), List.of(first, second)));
        when(mockGithubClient.downloadAsset(second)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(second.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/home/user/.local/bin/example"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "github:user/example"}));

        verify(mockGithubClient).downloadAsset(second);
        verify(mockGithubClient, never()).downloadAsset(first);
    }

    @Test
    void testExecuteInstallRejectsUnknownAsset() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getAsset()).thenReturn("missing.AppImage");

        Asset asset = new Asset("http://url", "app.AppImage");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));

        assertEquals(1, releaseManager.execute(new String[]{"install", "github:user/example"}));

        verify(mockGithubClient, never()).downloadAsset(any());
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
    }

    @Test
    void testExecuteInstallPassesVersionToPlatformHandler() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getVersion()).thenReturn("v1.2.3");

        Asset asset = new Asset("http://url", "example.AppImage");
        Path downloaded = Path.of("/tmp/downloaded.AppImage");
        Path versioned = Path.of("/state/versions/v1.2.3/example.AppImage");
        when(mockGithubClient.getRelease("user/example", "v1.2.3"))
            .thenReturn(new ReleaseAssets("v1.2.3", 1L, List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(versioned);
        when(mockPlatformHandler.launcherPath("github", "user/example"))
            .thenReturn(Path.of("/home/user/.local/bin/example"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "github:user/example"}));

        ArgumentCaptor<cz.cuni.mff.releasemanager.platform.InstallRequest> request =
            ArgumentCaptor.forClass(cz.cuni.mff.releasemanager.platform.InstallRequest.class);
        verify(mockPlatformHandler).install(request.capture());
        assertEquals("v1.2.3", request.getValue().version());
        assertEquals("github", request.getValue().provider());
        assertEquals("user/example", request.getValue().repoFullName());

        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals(versioned.toString(), captor.getValue().install().path());
        assertEquals(Path.of("/home/user/.local/bin/example").toString(),
            captor.getValue().install().uninstall().path());
    }

    @Test
    void testExecuteInstallQuietSuppressesProgress() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isQuiet()).thenReturn(true);

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockPlatformHandler.install(any(InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"install", "github:user/example", "--quiet"}));

        assertEquals("", output.trim());
    }

    @Test
    void testExecuteInstallJson() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        when(mockCmdParser.getVersion()).thenReturn("v1.2.3");

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        Path installed = Path.of("/ProgramFiles/example.exe");
        when(mockGithubClient.getRelease("user/example", "v1.2.3"))
            .thenReturn(new ReleaseAssets("v1.2.3", 1L, List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockPlatformHandler.install(any(InstallRequest.class))).thenReturn(installed);

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"install", "github:user/example", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals("user/example", root.get("installed").get("repo").asText());
        assertEquals("v1.2.3", root.get("installed").get("version").asText());
        assertEquals(installed.toString(), root.get("installed").get("path").asText());
    }

    @Test
    void testExecuteInstallDryRunJson() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        when(mockCmdParser.isDryRun()).thenReturn(true);

        Asset asset = new Asset("http://url", "example.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets("v1.0.0", 1L, List.of(asset), List.of(asset)));

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"install", "github:user/example", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertTrue(root.get("dry_run").asBoolean());
        assertEquals("install", root.get("action").asText());
        assertEquals("example.exe", root.get("asset").asText());
        verify(mockGithubClient, never()).downloadAsset(any());
    }

    @Test
    void testExecuteInstallDryRunDoesNotDownloadOrInstall() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isDryRun()).thenReturn(true);

        Asset asset = new Asset("http://url", "example.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));

        assertEquals(0, releaseManager.execute(new String[]{"install", "github:user/example"}));

        verify(mockGithubClient, never()).downloadAsset(any());
        verify(mockPlatformHandler, never()).install(any(InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUninstallDryRunKeepsInstallAndState() throws Exception {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isDryRun()).thenReturn(true);
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", "http://url", "example.exe",
                "/ProgramFiles/uninstall.exe")));

        assertEquals(0, releaseManager.execute(new String[]{"uninstall", "github:user/example"}));

        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockStateStore, never()).removeManifest(any(RepoRef.class));
    }

    @Test
    void testExecuteRejectsBareRepository() {
        Command cmd = Command.INSTALL;
        cmd.argument = "owner/repo";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(1, releaseManager.execute(new String[]{"install", "owner/repo"}));
        verifyNoInteractions(mockGithubClient);
    }

    @Test
    void testExecuteInstallJsonWithSeveralAssetsNeedsTheAssetOption() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        when(mockCmdParser.getUserInput()).thenReturn(1);
        Asset msi = new Asset("http://url/app.msi", "app.msi");
        Asset exe = new Asset("http://url/app.exe", "app.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(msi, exe), List.of(msi, exe)));

        assertEquals(1, releaseManager.execute(
            new String[]{"install", "github:user/example", "--json"}));

        verify(mockGithubClient, never()).downloadAsset(any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteInstallRefusesAnAlreadyInstalledRelease() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", "http://url", "example.exe", "/tmp/live")));

        assertEquals(1, releaseManager.execute(
            new String[]{"install", "github:user/example"}));

        verify(mockGithubClient, never()).getLatestRelease(any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteInstall() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "github:user/example"}));

        verify(mockGithubClient).downloadAsset(asset);
        verify(mockVerifier).verify(any(), any(), any(), any());
        verify(mockPlatformHandler).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));

        ArgumentCaptor<AppManifest> manifestCaptor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(manifestCaptor.capture());
        verify(mockStateStore).rebuildIndex();
        AppManifest saved = manifestCaptor.getValue();
        assertEquals("user/example", saved.repo());
        assertEquals("example.exe", saved.asset().name());
        assertEquals(Path.of("/ProgramFiles/example.exe").toString(),
            saved.install().path());
        assertEquals("FPR", saved.signature().identity());
        assertEquals("github", saved.providerType());
        assertEquals("https://api.github.com", saved.providerApi());
    }

    @Test
    void testExecuteInstallUnknownProviderFails() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "work:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(1, releaseManager.execute(
            new String[]{"install", "work:user/example"}));

        verify(mockGithubClient, never()).getLatestRelease(any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteInstallFailureReturnsErrorCode() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class))).thenReturn(null);

        assertEquals(1, releaseManager.execute(new String[]{"install", "github:user/example"}));
    }

    @Test
    void testExecuteAppliesConfiguredInstallRoot() throws Exception {
        Command cmd = Command.LIST;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            new GrmConfig(GrmConfig.KIND, GrmConfig.SCHEMA_VERSION, false, 1,
                tempDir.toString()),
            false,
            null));

        assertEquals(0, releaseManager.execute(new String[]{"list"}));

        verify(mockPlatformHandler).setInstallRoot(tempDir);
    }
}
