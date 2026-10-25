package cz.cuni.mff.releasemanager;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.InstallRequest;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.verify.SignatureResult;
import cz.cuni.mff.releasemanager.verify.VerificationResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleaseManagerUpdateSignerTest extends ReleaseManagerTestSupport {

    @Test
    void testExecuteUpdateRefusesSignerChange() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "OLD")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.verified("example.exe", "gpg", "New Signer", "NEW"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
        verify(mockBackupManager, never()).createAndPrune(any(), anyInt());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateAllowsSameSigner() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "FPR")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.verified("example.exe", "gpg", "Test Signer", "FPR"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "github:user/example"}));

        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("FPR", captor.getValue().signature().identity());
    }

    @Test
    void testExecuteUpdateRefusesUnsignedReleaseWhenPinned() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "FPR")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.missing("example.exe"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
    }

    @Test
    void testExecuteUpdateSignerChangeCanBeOverridden() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isAllowSignerChange()).thenReturn(true);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "OLD")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.verified("example.exe", "gpg", "New Signer", "NEW"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockPrompt, atLeastOnce()).confirm(any(), anyBoolean());
        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("NEW", captor.getValue().signature().identity());
    }

    @Test
    void testExecuteUpdateSignerChangeOverrideCanBeDeclined() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isAllowSignerChange()).thenReturn(true);
        when(mockPrompt.confirm(contains("signer change"), anyBoolean()))
            .thenReturn(false);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "OLD")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.verified("example.exe", "gpg", "New Signer", "NEW"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "github:user/example"}));

        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateUnsignedPinnedCanBeOverridden() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isAllowSignerChange()).thenReturn(true);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "OLD")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.missing("example.exe"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "github:user/example"}));

        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("missing", captor.getValue().signature().status());
        assertEquals(null, captor.getValue().signature().identity());
    }
}
