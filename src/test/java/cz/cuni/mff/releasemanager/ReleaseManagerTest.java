package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.Prompt;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.GrmIndex;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.SearchResult;
import cz.cuni.mff.releasemanager.verify.AssetVerifier;
import cz.cuni.mff.releasemanager.verify.SignatureResult;
import cz.cuni.mff.releasemanager.verify.SignatureVerifier;
import cz.cuni.mff.releasemanager.verify.VerificationResult;


class ReleaseManagerTest {

    @TempDir
    Path tempDir;

    private ReleaseManager releaseManager;
    private CmdParser mockCmdParser;
    private GithubClient mockGithubClient;
    private PlatformHandler mockPlatformHandler;
    private AssetVerifier mockVerifier;
    private SignatureVerifier mockSignatureVerifier;
    private StateStore mockStateStore;
    private BackupManager mockBackupManager;
    private AppPaths mockPaths;
    private Prompt mockPrompt;

    @BeforeEach
    void setUp() throws Exception {
        releaseManager = ReleaseManager.getInstance();

        mockCmdParser = mock(CmdParser.class);
        mockGithubClient = mock(GithubClient.class);
        mockPlatformHandler = mock(PlatformHandler.class);
        mockVerifier = mock(AssetVerifier.class);
        mockSignatureVerifier = mock(SignatureVerifier.class);
        mockStateStore = mock(StateStore.class);
        mockBackupManager = mock(BackupManager.class);
        mockPrompt = mock(Prompt.class);
        mockPaths = new AppPaths(
            Path.of(System.getProperty("java.io.tmpdir"), "grm-test-state"));

         // use reflection to set the private fields - replace with dependency injection framework
        Field parserField = ReleaseManager.class.getDeclaredField("cmdParser");
        parserField.setAccessible(true);
        parserField.set(releaseManager, mockCmdParser);

        Field clientField = ReleaseManager.class.getDeclaredField("githubClient");
        clientField.setAccessible(true);
        clientField.set(releaseManager, mockGithubClient);

        Field handlerField = ReleaseManager.class.getDeclaredField("platformHandler");
        handlerField.setAccessible(true);
        handlerField.set(releaseManager, mockPlatformHandler);

        Field verifierField = ReleaseManager.class.getDeclaredField("verifier");
        verifierField.setAccessible(true);
        verifierField.set(releaseManager, mockVerifier);

        Field signatureField = ReleaseManager.class.getDeclaredField("signatureVerifier");
        signatureField.setAccessible(true);
        signatureField.set(releaseManager, mockSignatureVerifier);

        Field stateField = ReleaseManager.class.getDeclaredField("stateStore");
        stateField.setAccessible(true);
        stateField.set(releaseManager, mockStateStore);

        Field promptField = ReleaseManager.class.getDeclaredField("prompt");
        promptField.setAccessible(true);
        promptField.set(releaseManager, mockPrompt);

        Field backupField = ReleaseManager.class.getDeclaredField("backupManager");
        backupField.setAccessible(true);
        backupField.set(releaseManager, mockBackupManager);

        setOs(Platform.OS.LINUX);

        when(mockStateStore.paths()).thenReturn(mockPaths);
        when(mockBackupManager.createAndPrune(any(), anyInt())).thenReturn(List.of());
        when(mockSignatureVerifier.verify(any(), any(), any())).thenReturn(
            SignatureResult.verified("example.exe", "gpg", "Test Signer", "FPR"));
        when(mockStateStore.loadConfig()).thenReturn(
            new ConfigStore.LoadResult(GrmConfig.defaults(), false, null));
        when(mockStateStore.scanManifests()).thenReturn(List.of());
        when(mockStateStore.loadIndex()).thenReturn(Optional.of(GrmIndex.empty()));
        when(mockStateStore.findRecoveryIssues(any(), any())).thenReturn(List.of());
    }

    private void setOs(Platform.OS os) throws Exception {
        Field field = ReleaseManager.class.getDeclaredField("os");
        field.setAccessible(true);
        field.set(releaseManager, os);
    }

    private static AppManifest manifest(
        String repo, String assetUrl, String assetName, String uninstallPath) {
        return new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            "github",
            repo,
            "1.0.0",
            1L,
            new AppManifest.Asset(assetName, assetUrl, null, null),
            new AppManifest.Install(
                "exe",
                uninstallPath,
                "/tmp/root",
                new AppManifest.Uninstall(uninstallPath, List.of(), null)),
            new AppManifest.Verification("missing", "sha256", null, null, null),
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static AppManifest withSignature(AppManifest base, String identity) {
        return new AppManifest(
            base.kind(),
            base.schemaVersion(),
            base.provider(),
            base.repo(),
            base.version(),
            base.releaseId(),
            base.asset(),
            base.install(),
            base.verification(),
            base.backups(),
            base.installedAt(),
            base.updatedAt(),
            new AppManifest.Signature("verified", "gpg", "Old Signer", identity));
    }

    private static AppManifest withProductCode(AppManifest base, String productCode) {
        AppManifest.Install install = base.install();
        AppManifest.Uninstall uninstall = new AppManifest.Uninstall(
            install.uninstall().path(), install.uninstall().args(), productCode);
        return new AppManifest(
            base.kind(),
            base.schemaVersion(),
            base.provider(),
            base.repo(),
            base.version(),
            base.releaseId(),
            base.asset(),
            new AppManifest.Install(install.kind(), install.path(), install.root(), uninstall),
            base.verification(),
            base.backups(),
            base.installedAt(),
            base.updatedAt(),
            base.signature());
    }

    @Test
    void testExecuteSearch() {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        var repo = new Repo("user/example", "Example repo");
        var searchResult = Optional.of(new SearchResult(List.of(repo)));

        when(mockGithubClient.searchRepoByName("example")).thenReturn(searchResult);

        assertEquals(0, releaseManager.execute(new String[] {"search", "example"}));

        verify(mockGithubClient).searchRepoByName("example");
    }

    @Test
    void testExecuteInstall() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).downloadAsset(asset);
        verify(mockVerifier).verify(asset, List.of(asset), downloaded);
        verify(mockGithubClient).installDownloadedAsset(downloaded, "user/example");

        ArgumentCaptor<AppManifest> manifestCaptor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(manifestCaptor.capture());
        verify(mockStateStore).rebuildIndex();
        AppManifest saved = manifestCaptor.getValue();
        assertEquals("user/example", saved.repo());
        assertEquals("example.exe", saved.asset().name());
        assertEquals(Path.of("/ProgramFiles/example.exe").toString(),
            saved.install().path());
        assertEquals("FPR", saved.signature().identity());
    }

    @Test
    void testExecuteInstallPassesAllAssetsToVerifier() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        Asset checksum = new Asset("http://url", "example.exe.sha256");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(
                List.of(asset, checksum), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset, checksum), downloaded))
            .thenReturn(VerificationResult.verified(asset.name(), checksum.name()));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockVerifier).verify(asset, List.of(asset, checksum), downloaded);
    }

    @Test
    void testExecuteInstallAbortsOnInvalidSignature() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isAllowSignerChange()).thenReturn(true);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockSignatureVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(SignatureResult.invalid("example.exe", "gpg", "BAD signature"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteInstallRequiresSignatureWhenRequested() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isRequireSignature()).thenReturn(true);
        when(mockCmdParser.isAllowSignerChange()).thenReturn(true);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockSignatureVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(SignatureResult.missing("example.exe"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
    }

    @Test
    void testExecuteInstallContinuesWithoutSignatureByDefault() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockSignatureVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(SignatureResult.missing("example.exe"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).installDownloadedAsset(downloaded, "user/example");
    }

    @Test
    void testExecuteInstallVerifiesChecksumBeforeSignature() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        InOrder order = inOrder(mockVerifier, mockSignatureVerifier);
        order.verify(mockVerifier).verify(asset, List.of(asset), downloaded);
        order.verify(mockSignatureVerifier).verify(asset, List.of(asset), downloaded);
    }

    @Test
    void testExecuteUninstall() throws IOException {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        AppManifest manifest = manifest(
            "user/example", "http://url", "example.exe", "/ProgramFiles/uninstall.exe");
        Path uninstallPath = Path.of("/ProgramFiles/uninstall.exe");
        when(mockStateStore.findManifest("user/example")).thenReturn(Optional.of(manifest));
        when(mockPlatformHandler.uninstall(uninstallPath)).thenReturn(true);
        when(mockStateStore.removeManifest("user/example")).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"uninstall", "user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath);
        verify(mockStateStore).removeManifest("user/example");
        verify(mockStateStore).rebuildIndex();
    }

    @Test
    void testExecuteUninstallDoesNotRemoveRecordWhenUninstallFails() throws IOException {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        AppManifest manifest = manifest(
            "user/example", "http://url", "example.exe", "/ProgramFiles/uninstall.exe");
        Path uninstallPath = Path.of("/ProgramFiles/uninstall.exe");
        when(mockStateStore.findManifest("user/example")).thenReturn(Optional.of(manifest));
        when(mockPlatformHandler.uninstall(uninstallPath)).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"uninstall", "user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath);
        verify(mockStateStore, never()).removeManifest(any());
    }

    @Test
    void testExecuteUninstallUsesProductCode() throws Exception {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        String productCode = "{11111111-2222-3333-4444-555555555555}";
        AppManifest manifest = withProductCode(
            manifest("user/example", "http://url", "example.exe",
                "/ProgramFiles/uninstall.exe"),
            productCode);
        Path uninstallPath = Path.of("/ProgramFiles/uninstall.exe");
        when(mockStateStore.findManifest("user/example")).thenReturn(Optional.of(manifest));
        when(mockPlatformHandler.uninstall(uninstallPath, productCode)).thenReturn(true);
        when(mockStateStore.removeManifest("user/example")).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"uninstall", "user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath, productCode);
        verify(mockPlatformHandler, never()).uninstall(uninstallPath);
        verify(mockStateStore).removeManifest("user/example");
    }

    @Test
    void testExecuteInstallStoresProductCode() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        Path installed = Path.of("/ProgramFiles/installer.msi");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(installed);
        String productCode = "{11111111-2222-3333-4444-555555555555}";
        when(mockPlatformHandler.productCode(installed)).thenReturn(productCode);

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals(productCode, captor.getValue().install().uninstall().productCode());
    }

    @Test
    void testExecuteInfo() throws Exception {
        Command cmd = Command.INFO;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(manifest(
                "user/example", "http://url", "example.exe", "/tmp/live")));

        assertEquals(0, releaseManager.execute(new String[]{"info", "user/example"}));

        verify(mockStateStore).findManifest("user/example");
    }

    @Test
    void testExecuteInfoMissing() throws Exception {
        Command cmd = Command.INFO;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.findManifest("user/example")).thenReturn(Optional.empty());

        assertEquals(1, releaseManager.execute(new String[]{"info", "user/example"}));
    }

    @Test
    void testExecuteUpdate() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient).downloadAsset(newAsset);
        verify(mockGithubClient).installDownloadedAsset(downloaded, "user/example");
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
    void testExecuteUpdateRefusesSignerChange() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "OLD")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(SignatureResult.verified("example.exe", "gpg", "New Signer", "NEW"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockBackupManager, never()).createAndPrune(any(), anyInt());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateAllowsSameSigner() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "FPR")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(SignatureResult.verified("example.exe", "gpg", "Test Signer", "FPR"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("FPR", captor.getValue().signature().identity());
    }

    @Test
    void testExecuteUpdateRefusesUnsignedReleaseWhenPinned() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "FPR")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(SignatureResult.missing("example.exe"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
        verify(mockGithubClient).discardAsset(downloaded);
    }

    @Test
    void testExecuteUpdateSignerChangeCanBeOverridden() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isAllowSignerChange()).thenReturn(true);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "OLD")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(SignatureResult.verified("example.exe", "gpg", "New Signer", "NEW"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockPrompt).confirm(any(), anyBoolean());
        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("NEW", captor.getValue().signature().identity());
    }

    @Test
    void testExecuteUpdateSignerChangeOverrideCanBeDeclined() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isAllowSignerChange()).thenReturn(true);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(false);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "OLD")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(SignatureResult.verified("example.exe", "gpg", "New Signer", "NEW"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateUnsignedPinnedCanBeOverridden() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isAllowSignerChange()).thenReturn(true);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", oldAsset.url(), oldAsset.name(),
                    "/ProgramFiles/uninstall.exe"),
                "OLD")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockSignatureVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(SignatureResult.missing("example.exe"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("missing", captor.getValue().signature().status());
        assertEquals(null, captor.getValue().signature().identity());
    }

    @Test
    void testExecuteInstallRefusesSignerChangeWhenAlreadyPinned() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", "http://oldurl", "example.exe",
                    "/ProgramFiles/uninstall.exe"),
                "OLD")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockSignatureVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(SignatureResult.verified("example.exe", "gpg", "New Signer", "NEW"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
        verify(mockGithubClient).discardAsset(downloaded);
    }

    @Test
    void testExecuteUpdateInstallsWithoutUninstallingOld() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        InOrder inOrder = inOrder(mockGithubClient, mockVerifier);
        inOrder.verify(mockGithubClient).downloadAsset(newAsset);
        inOrder.verify(mockVerifier).verify(newAsset, List.of(newAsset), downloaded);
        inOrder.verify(mockGithubClient).installDownloadedAsset(downloaded, "user/example");
        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockStateStore).saveManifest(any());
    }

    @Test
    void testExecuteUpdateInstallFailureReturnsError() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example")).thenReturn(null);

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockGithubClient).installDownloadedAsset(downloaded, "user/example");
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateFailsOnChecksumMismatch() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.mismatch(
                newAsset.name(), "expected", "actual", "checksum"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateDoesNotUninstallWhenDownloadFails() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenThrow(new IOException("boom"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateAlreadyUpToDate() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        when(mockStateStore.findManifest("user/example"))
            .thenReturn(Optional.of(manifest(
                "user/example", asset.url(), asset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient, never()).downloadAsset(any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUsageReturnsUsageCode() {
        Command cmd = Command.USAGE;
        cmd.argument = null;
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(2, releaseManager.execute(new String[]{"bad"}));
    }

    @Test
    void testExecuteSearchFailureReturnsErrorCode() {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockGithubClient.searchRepoByName("example")).thenReturn(Optional.empty());

        assertEquals(1, releaseManager.execute(new String[]{"search", "example"}));
    }

    @Test
    void testExecuteInstallFailureReturnsErrorCode() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example")).thenReturn(null);

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));
    }

    @Test
    void testExecuteInstallFailsOnChecksumMismatch() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.mismatch(
                asset.name(), "expected", "actual", "checksum"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
    }

    @Test
    void testExecuteInstallStrictFailsWhenChecksumMissing() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isStrict()).thenReturn(true);
        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.missing(asset.name()));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
    }

    @Test
    void testExecuteInstallStrictFromConfigWhenChecksumMissing() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            new GrmConfig(GrmConfig.KIND, GrmConfig.SCHEMA_VERSION, true, 1, null),
            false,
            null));
        Asset asset = new Asset("http://url", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new GithubClient.ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.missing(asset.name()));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
    }

    @Test
    void testExecuteListRestoresMissingManifestWhenConfirmed() throws Exception {
        Command cmd = Command.LIST;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest snapshot = manifest(
            "owner/repo", "http://url", "example.exe", "/tmp/live");
        GrmIndex.Entry entry = new GrmIndex.Entry(
            snapshot.repo(),
            snapshot.version(),
            snapshot.installedAt(),
            "/tmp/live",
            "applications/github/owner__repo/grm_manifest.json",
            snapshot);
        when(mockStateStore.loadIndex()).thenReturn(Optional.of(
            new GrmIndex(GrmIndex.KIND, GrmIndex.SCHEMA_VERSION, Instant.now(), List.of(entry))));
        when(mockStateStore.findRecoveryIssues(any(), any())).thenReturn(List.of(
            new StateStore.RecoveryIssue(
                StateStore.RecoveryKind.RESTORE_MANIFEST, entry)));
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"list"}));

        verify(mockStateStore).saveManifest(snapshot);
        verify(mockStateStore).rebuildIndex();
    }

    @Test
    void testExecuteListAbortsWhenRecoveryDeclined() throws Exception {
        Command cmd = Command.LIST;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest snapshot = manifest(
            "owner/repo", "http://url", "example.exe", "/tmp/live");
        GrmIndex.Entry entry = new GrmIndex.Entry(
            snapshot.repo(),
            snapshot.version(),
            snapshot.installedAt(),
            "/tmp/live",
            "applications/github/owner__repo/grm_manifest.json",
            snapshot);
        when(mockStateStore.loadIndex()).thenReturn(Optional.of(
            new GrmIndex(GrmIndex.KIND, GrmIndex.SCHEMA_VERSION, Instant.now(), List.of(entry))));
        when(mockStateStore.findRecoveryIssues(any(), any())).thenReturn(List.of(
            new StateStore.RecoveryIssue(
                StateStore.RecoveryKind.RESTORE_MANIFEST, entry)));
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"list"}));

        verify(mockStateStore, never()).saveManifest(any());
        verify(mockStateStore, never()).rebuildIndex();
    }

    @Test
    void testExecuteListDropsStaleIndexEntryWhenConfirmed() throws Exception {
        Command cmd = Command.LIST;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        GrmIndex.Entry entry = new GrmIndex.Entry(
            "owner/repo", "1.0.0", Instant.now(), "/tmp/missing",
            "applications/github/owner__repo/grm_manifest.json", null);
        when(mockStateStore.loadIndex()).thenReturn(Optional.of(
            new GrmIndex(GrmIndex.KIND, GrmIndex.SCHEMA_VERSION, Instant.now(), List.of(entry))));
        when(mockStateStore.findRecoveryIssues(any(), any())).thenReturn(List.of(
            new StateStore.RecoveryIssue(
                StateStore.RecoveryKind.DROP_INDEX_ENTRY, entry)));
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"list"}));

        verify(mockStateStore, never()).saveManifest(any());
        verify(mockStateStore).rebuildIndex();
    }

    @Test
    void testExecuteList() throws IOException {
        Command cmd = Command.LIST;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.scanManifests()).thenReturn(List.of(
            manifest("user/example", "http://url", "example.exe", "/ProgramFiles/uninstall.exe")));

        assertEquals(0, releaseManager.execute(new String[]{"list"}));
        verify(mockStateStore, atLeastOnce()).scanManifests();
    }

    private static AppManifest.Backup backup(String version) {
        return new AppManifest.Backup(
            version,
            "/backup/" + version,
            "/backup/" + version + "/artifact",
            "/tmp/live",
            Instant.parse("2026-01-01T00:00:00Z"),
            "appimage",
            "/backup/" + version + "/grm_backup_manifest.json");
    }

    private AppPaths tempPaths() throws IOException {
        AppPaths paths = new AppPaths(tempDir);
        when(mockStateStore.paths()).thenReturn(paths);
        Files.createDirectories(paths.root());
        Files.createFile(paths.configFile());
        return paths;
    }

    @Test
    void testExecuteRollbackRestoresLatestBackup() throws Exception {
        Command cmd = Command.ROLLBACK;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest current = manifest(
            "user/example", "http://new", "example.exe", "/tmp/live");
        AppManifest restored = manifest(
            "user/example", "http://old", "example.exe", "/tmp/live");
        AppManifest.Backup backup = backup("0.9.0");
        when(mockStateStore.findManifest("user/example")).thenReturn(Optional.of(current));
        when(mockBackupManager.latest(current)).thenReturn(Optional.of(backup));
        when(mockBackupManager.readManifest(backup)).thenReturn(restored);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"rollback", "user/example"}));

        verify(mockBackupManager).restore(backup, Path.of("/tmp/live"));
        verify(mockStateStore).saveManifest(any());
        verify(mockStateStore).rebuildIndex();
    }

    @Test
    void testExecuteRollbackRefusesWithoutBackups() throws Exception {
        Command cmd = Command.ROLLBACK;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest current = manifest(
            "user/example", "http://new", "example.exe", "/tmp/live");
        when(mockStateStore.findManifest("user/example")).thenReturn(Optional.of(current));
        when(mockBackupManager.latest(current)).thenReturn(Optional.empty());

        assertEquals(1, releaseManager.execute(new String[]{"rollback", "user/example"}));

        verify(mockBackupManager, never()).restore(any(), any());
    }

    @Test
    void testExecuteRollbackCanBeDeclined() throws Exception {
        Command cmd = Command.ROLLBACK;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest current = manifest(
            "user/example", "http://new", "example.exe", "/tmp/live");
        AppManifest.Backup backup = backup("0.9.0");
        when(mockStateStore.findManifest("user/example")).thenReturn(Optional.of(current));
        when(mockBackupManager.latest(current)).thenReturn(Optional.of(backup));
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"rollback", "user/example"}));

        verify(mockBackupManager, never()).restore(any(), any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteRollbackRefusedOnWindows() throws Exception {
        setOs(Platform.OS.WINDOWS);
        Command cmd = Command.ROLLBACK;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(1, releaseManager.execute(new String[]{"rollback", "user/example"}));

        verify(mockStateStore, never()).findManifest(any());
        verify(mockBackupManager, never()).latest(any());
    }

    @Test
    void testExecuteDoctorSkipsOrphansOutsideLinux() throws Exception {
        setOs(Platform.OS.MAC);
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.writeString(bin.resolve("owner__repo"), "data");
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);

        assertEquals(0, releaseManager.execute(new String[]{"doctor"}));

        verify(mockPlatformHandler, never()).getInstallRoot();
    }

    @Test
    void testExecuteAdoptRefusedOutsideLinux() throws Exception {
        setOs(Platform.OS.MAC);
        Command cmd = Command.ADOPT;
        cmd.argument = "owner/repo";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(1, releaseManager.execute(new String[]{"adopt", "owner/repo"}));

        verify(mockStateStore, never()).findManifest(any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteDoctorAppliesConfiguredInstallRoot() throws Exception {
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            new GrmConfig(GrmConfig.KIND, GrmConfig.SCHEMA_VERSION, false, 1,
                tempDir.toString()),
            false,
            null));
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);

        assertEquals(0, releaseManager.execute(new String[]{"doctor"}));

        verify(mockPlatformHandler).setInstallRoot(tempDir);
    }

    @Test
    void testExecuteDoctorHealthy() throws Exception {
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);

        assertEquals(0, releaseManager.execute(new String[]{"doctor"}));
    }

    @Test
    void testExecuteDoctorFindsOrphan() throws Exception {
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.writeString(bin.resolve("owner__repo"), "data");
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);

        assertEquals(1, releaseManager.execute(new String[]{"doctor"}));
    }

    @Test
    void testExecuteAdoptWritesManifest() throws Exception {
        Command cmd = Command.ADOPT;
        cmd.argument = "owner/repo";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.writeString(bin.resolve("owner__repo"), "data");
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);
        when(mockStateStore.findManifest("owner/repo")).thenReturn(Optional.empty());
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"adopt", "owner/repo"}));

        verify(mockStateStore).saveManifest(any());
        verify(mockStateStore).rebuildIndex();
    }

    @Test
    void testExecuteAdoptCanBeDeclined() throws Exception {
        Command cmd = Command.ADOPT;
        cmd.argument = "owner/repo";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.writeString(bin.resolve("owner__repo"), "data");
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);
        when(mockStateStore.findManifest("owner/repo")).thenReturn(Optional.empty());
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"adopt", "owner/repo"}));

        verify(mockStateStore, never()).saveManifest(any());
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

    @Test
    void testExecuteHelp() {
        Command cmd = Command.HELP;
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(0, releaseManager.execute(new String[]{"help"}));
        verify(mockCmdParser).parse(any());
    }
}