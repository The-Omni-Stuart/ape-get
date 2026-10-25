package cz.cuni.mff.releasemanager;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.InstallRequest;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.verify.SignatureResult;
import cz.cuni.mff.releasemanager.verify.VerificationResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleaseManagerInstallTrustTest extends ReleaseManagerTestSupport {

    @Test
    void testExecuteInstallPassesAllAssetsToVerifier() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        Asset checksum = new Asset("http://url", "example.exe.sha256");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(
                List.of(asset, checksum), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), checksum.name()));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "github:user/example"}));

        verify(mockVerifier).verify(any(), any(), any(), any());
    }

    @Test
    void testExecuteInstallAbortsOnInvalidSignature() throws Exception {
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
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.invalid("example.exe", "gpg", "BAD signature"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "github:user/example"}));
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteInstallRequiresSignatureWhenRequested() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isRequireSignature()).thenReturn(true);
        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.missing("example.exe"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "github:user/example"}));
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
    }

    @Test
    void testExecuteInstallContinuesWithoutSignatureByDefault() throws Exception {
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
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.missing("example.exe"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "github:user/example"}));

        verify(mockPlatformHandler).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
    }

    @Test
    void testExecuteInstallVerifiesChecksumBeforeSignature() throws Exception {
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

        InOrder order = inOrder(mockVerifier, mockSignatureVerifier);
        order.verify(mockVerifier).verify(any(), any(), any(), any());
        order.verify(mockSignatureVerifier).verify(any(), any(), any(), any());
    }

    @Test
    void testExecuteInstallStoresProductCode() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        Path installed = Path.of("/ProgramFiles/installer.msi");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(installed);
        String productCode = "{11111111-2222-3333-4444-555555555555}";
        when(mockPlatformHandler.productCode(installed)).thenReturn(productCode);

        assertEquals(0, releaseManager.execute(new String[]{"install", "github:user/example"}));

        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals(productCode, captor.getValue().install().uninstall().productCode());
    }

    @Test
    void testExecuteInstallFailsOnChecksumMismatch() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.mismatch(
                asset.name(), "expected", "actual", "checksum"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "github:user/example"}));
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
    }

    @Test
    void testExecuteInstallStrictFailsWhenChecksumMissing() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isStrict()).thenReturn(true);
        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.missing(asset.name()));

        assertEquals(1, releaseManager.execute(new String[]{"install", "github:user/example"}));
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
    }

    @Test
    void testExecuteInstallStrictFromConfigWhenChecksumMissing() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            new GrmConfig(GrmConfig.KIND, GrmConfig.SCHEMA_VERSION, true, 1, null),
            false,
            null));
        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.missing(asset.name()));

        assertEquals(1, releaseManager.execute(new String[]{"install", "github:user/example"}));
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
    }
}
