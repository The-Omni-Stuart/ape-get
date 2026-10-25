package cz.cuni.mff.releasemanager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.Prompt;
import cz.cuni.mff.releasemanager.platform.InstallRequest;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.SearchService;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.GrmIndex;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.RepoRef;
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

        Field searchField = ReleaseManager.class.getDeclaredField("searchService");
        searchField.setAccessible(true);
        searchField.set(releaseManager, new SearchService());

        setOs(Platform.OS.LINUX);

        when(mockStateStore.paths()).thenReturn(mockPaths);
        when(mockBackupManager.createAndPrune(any(), anyInt())).thenReturn(List.of());
        when(mockSignatureVerifier.verify(any(), any(), any(), any())).thenReturn(
            SignatureResult.verified("example.exe", "gpg", "Test Signer", "FPR"));
        when(mockStateStore.loadConfig()).thenReturn(
            new ConfigStore.LoadResult(GrmConfig.defaults(), false, null));
        when(mockStateStore.scanManifests()).thenReturn(List.of());
        when(mockStateStore.loadIndex()).thenReturn(Optional.of(GrmIndex.empty()));
        when(mockStateStore.findRecoveryIssues(any(), any())).thenReturn(List.of());
        when(mockPlatformHandler.installState(any()))
            .thenReturn(PlatformHandler.InstallState.INSTALLED);
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

    private static AppManifest withProvider(AppManifest base, String provider) {
        return new AppManifest(
            base.kind(),
            base.schemaVersion(),
            provider,
            base.repo(),
            base.version(),
            base.releaseId(),
            base.asset(),
            base.install(),
            base.verification(),
            base.backups(),
            base.installedAt(),
            base.updatedAt(),
            base.signature());
    }

    private static AppManifest withProviderMirror(
        AppManifest base, String provider, String type, String api) {
        return new AppManifest(
            base.kind(),
            base.schemaVersion(),
            provider,
            base.repo(),
            base.version(),
            base.releaseId(),
            base.asset(),
            base.install(),
            base.verification(),
            base.backups(),
            base.installedAt(),
            base.updatedAt(),
            base.signature(),
            type,
            api);
    }

    private static GrmConfig configWithProvider(String name, String type, String api) {
        return new GrmConfig(
            GrmConfig.KIND,
            GrmConfig.SCHEMA_VERSION,
            false,
            1,
            null,
            false,
            Map.of(name, new ProviderConfig(type, "https://git.example.com", api, null)));
    }

    @Test
    void testExecuteSearch() {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("github");

        var repo = new Repo("user/example", "Example repo");
        var searchResult = Optional.of(new SearchResult(List.of(repo)));

        when(mockGithubClient.searchRepoByName("example")).thenReturn(searchResult);

        assertEquals(0, releaseManager.execute(new String[] {"search", "example"}));

        verify(mockGithubClient).searchRepoByName("example");
    }

    @Test
    void testExecuteSearchUsesProviderOption() {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("github");
        when(mockGithubClient.searchRepoByName("example")).thenReturn(Optional.of(
            new SearchResult(List.of(new Repo("user/example", "Example repo")))));

        assertEquals(0, releaseManager.execute(
            new String[]{"search", "example", "--provider", "github"}));
        verify(mockGithubClient).searchRepoByName("example");
    }

    @Test
    void testExecuteSearchUnknownProviderFails() {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("work");

        assertEquals(1, releaseManager.execute(
            new String[]{"search", "example", "--provider", "work"}));
        verify(mockGithubClient, never()).searchRepoByName(any());
    }

    @Test
    void testExecuteSearchAllProviders() throws Exception {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("all");

        SearchService service = mock(SearchService.class);
        when(service.searchAll(any(), eq("example"), any())).thenReturn(List.of(
            new SearchService.ProviderResult(
                "github", List.of(new Repo("a/b", "A")), null),
            new SearchService.ProviderResult("codeberg", List.of(), "down")));
        Field field = ReleaseManager.class.getDeclaredField("searchService");
        field.setAccessible(true);
        field.set(releaseManager, service);

        assertEquals(0, releaseManager.execute(
            new String[]{"search", "example", "--provider", "all"}));
        verify(service).searchAll(any(), eq("example"), any());
    }

    @Test
    void testExecuteSearchDefaultsToAllProviders() throws Exception {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        SearchService service = mock(SearchService.class);
        when(service.searchAll(any(), eq("example"), any())).thenReturn(List.of(
            new SearchService.ProviderResult(
                "github", List.of(new Repo("a/b", "A")), null)));
        Field field = ReleaseManager.class.getDeclaredField("searchService");
        field.setAccessible(true);
        field.set(releaseManager, service);

        assertEquals(0, releaseManager.execute(new String[]{"search", "example"}));
        verify(service).searchAll(any(), eq("example"), any());
    }

    @Test
    void testExecuteSearchDefaultsToGithubWhenAllDisabled() throws Exception {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            new GrmConfig(
                GrmConfig.KIND,
                GrmConfig.SCHEMA_VERSION,
                false,
                1,
                null,
                false,
                Map.of(),
                false),
            false,
            null));
        when(mockGithubClient.searchRepoByName("example")).thenReturn(Optional.of(
            new SearchResult(List.of(new Repo("user/example", "Example repo")))));

        assertEquals(0, releaseManager.execute(new String[]{"search", "example"}));
        verify(mockGithubClient).searchRepoByName("example");
    }

    @Test
    void testExecuteInstallUsesRequestedVersion() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient, never()).getLatestRelease(any());
        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("v1.2.3", captor.getValue().version());
        assertEquals(11L, captor.getValue().releaseId());
    }

    @Test
    void testExecuteInstallSelectsAssetByName() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).downloadAsset(chosen);
        verify(mockGithubClient, never()).downloadAsset(first);
    }

    @Test
    void testExecuteInstallSelectsAssetByIndex() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).downloadAsset(second);
        verify(mockGithubClient, never()).downloadAsset(first);
    }

    @Test
    void testExecuteInstallRejectsUnknownAsset() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getAsset()).thenReturn("missing.AppImage");

        Asset asset = new Asset("http://url", "app.AppImage");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient, never()).downloadAsset(any());
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
    }

    @Test
    void testExecuteInstallPassesVersionToPlatformHandler() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
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
        when(mockPlatformHandler.launcherPath("user/example"))
            .thenReturn(Path.of("/home/user/.local/bin/example"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

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
        cmd.argument = "user/example";
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
            () -> releaseManager.execute(new String[]{"install", "user/example", "--quiet"}));

        assertEquals("", output.trim());
    }

    @Test
    void testExecuteRollbackOnWindowsReinstallsBackupInstaller() throws Exception {
        setOs(Platform.OS.WINDOWS);
        Command cmd = Command.ROLLBACK;
        cmd.argument = "user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"rollback", "user/example"}));

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
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest current = manifest(
            "user/example", "http://v2", "app.exe", "/ProgramFiles/app/uninst.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(current));
        when(mockPlatformHandler.supportsInstallerRollback(current)).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"rollback", "user/example"}));

        verify(mockBackupManager, never()).latest(any());
        verify(mockPlatformHandler, never()).rollbackInstaller(any(), any(), any());
    }

    @Test
    void testExecuteRollbackOnWindowsStopsWhenInstallerRollbackFails() throws Exception {
        setOs(Platform.OS.WINDOWS);
        Command cmd = Command.ROLLBACK;
        cmd.argument = "user/example";
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

        assertEquals(1, releaseManager.execute(new String[]{"rollback", "user/example"}));

        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateSkipsBackupForWindowsNsis() throws Exception {
        setOs(Platform.OS.WINDOWS);
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockBackupManager, never()).createAndPrune(any(), anyInt());
    }

    @Test
    void testExecuteUpdateCreatesBackupForWindowsMsi() throws Exception {
        setOs(Platform.OS.WINDOWS);
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockBackupManager).createAndPrune(any(), anyInt());
    }

    @Test
    void testExecuteInstallDryRunDoesNotDownloadOrInstall() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isDryRun()).thenReturn(true);

        Asset asset = new Asset("http://url", "example.exe");
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient, never()).downloadAsset(any());
        verify(mockPlatformHandler, never()).install(any(InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateDryRunStopsBeforeDownload() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isDryRun()).thenReturn(true);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient, never()).downloadAsset(any());
        verify(mockBackupManager, never()).createAndPrune(any(), anyInt());
        verify(mockPlatformHandler, never()).install(any(InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUninstallDryRunKeepsInstallAndState() throws Exception {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isDryRun()).thenReturn(true);
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", "http://url", "example.exe",
                "/ProgramFiles/uninstall.exe")));

        assertEquals(0, releaseManager.execute(new String[]{"uninstall", "user/example"}));

        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockStateStore, never()).removeManifest(any(RepoRef.class));
    }

    @Test
    void testExecuteRollbackDryRunStopsBeforeRestore() throws Exception {
        Command cmd = Command.ROLLBACK;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isDryRun()).thenReturn(true);
        AppManifest current = manifest(
            "user/example", "http://new", "example.exe", "/tmp/live");
        AppManifest.Backup backup = backup("0.9.0");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(current));
        when(mockBackupManager.latest(current)).thenReturn(Optional.of(backup));

        assertEquals(0, releaseManager.execute(new String[]{"rollback", "user/example"}));

        verify(mockBackupManager, never()).restore(any(), any());
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteInstall() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

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
    void testExecuteInstallPassesAllAssetsToVerifier() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockVerifier).verify(any(), any(), any(), any());
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
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.invalid("example.exe", "gpg", "BAD signature"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
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
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.missing("example.exe"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
    }

    @Test
    void testExecuteInstallContinuesWithoutSignatureByDefault() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockPlatformHandler).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
    }

    @Test
    void testExecuteInstallVerifiesChecksumBeforeSignature() throws Exception {
        Command cmd = Command.INSTALL;
        cmd.argument = "user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        InOrder order = inOrder(mockVerifier, mockSignatureVerifier);
        order.verify(mockVerifier).verify(any(), any(), any(), any());
        order.verify(mockSignatureVerifier).verify(any(), any(), any(), any());
    }

    @Test
    void testExecuteInstallAcceptsGitHubProviderPrefix() throws Exception {
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

        assertEquals(0, releaseManager.execute(
            new String[]{"install", "github:user/example"}));

        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("github", captor.getValue().provider());
        assertEquals("user/example", captor.getValue().repo());
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
    void testExecuteInfoAcceptsGitHubProviderPrefix() throws Exception {
        Command cmd = Command.INFO;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", "http://url", "example.exe", "/tmp/live")));

        assertEquals(0, releaseManager.execute(
            new String[]{"info", "github:user/example"}));
    }

    @Test
    void testExecuteAdoptStoresProvider() throws Exception {
        Command cmd = Command.ADOPT;
        cmd.argument = "work:owner/repo";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.writeString(bin.resolve("owner__repo"), "data");
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);
        when(mockStateStore.findManifest(new RepoRef("work", "owner/repo")))
            .thenReturn(Optional.empty());
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        assertEquals(0, releaseManager.execute(
            new String[]{"adopt", "work:owner/repo"}));

        ArgumentCaptor<AppManifest> captor = ArgumentCaptor.forClass(AppManifest.class);
        verify(mockStateStore).saveManifest(captor.capture());
        assertEquals("work", captor.getValue().provider());
        assertEquals("owner/repo", captor.getValue().repo());
    }

    @Test
    void testExecuteUninstall() throws IOException {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        AppManifest manifest = manifest(
            "user/example", "http://url", "example.exe", "/ProgramFiles/uninstall.exe");
        Path uninstallPath = Path.of("/ProgramFiles/uninstall.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(manifest));
        when(mockPlatformHandler.uninstall(uninstallPath)).thenReturn(true);
        when(mockStateStore.removeManifest(new RepoRef("github", "user/example"))).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"uninstall", "user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath);
        verify(mockStateStore).removeManifest(new RepoRef("github", "user/example"));
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
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(manifest));
        when(mockPlatformHandler.uninstall(uninstallPath)).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"uninstall", "user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath);
        verify(mockStateStore, never()).removeManifest(any(RepoRef.class));
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
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(manifest));
        when(mockPlatformHandler.uninstall(uninstallPath, productCode)).thenReturn(true);
        when(mockStateStore.removeManifest(new RepoRef("github", "user/example"))).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"uninstall", "user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath, productCode);
        verify(mockPlatformHandler, never()).uninstall(uninstallPath);
        verify(mockStateStore).removeManifest(new RepoRef("github", "user/example"));
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
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class)))
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
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", "http://url", "example.exe", "/tmp/live")));

        assertEquals(0, releaseManager.execute(new String[]{"info", "user/example"}));

        verify(mockStateStore).findManifest(new RepoRef("github", "user/example"));
    }

    @Test
    void testExecuteInfoMissing() throws Exception {
        Command cmd = Command.INFO;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.empty());

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

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

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
    void testExecuteUpdateRefusesSignerChange() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
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

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
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

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
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

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
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
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(withSignature(
                manifest("user/example", "http://oldurl", "example.exe",
                    "/ProgramFiles/uninstall.exe"),
                "OLD")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockSignatureVerifier.verify(any(), any(), any(), any()))
            .thenReturn(SignatureResult.verified("example.exe", "gpg", "New Signer", "NEW"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
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

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

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
        cmd.argument = "user/example";
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

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockPlatformHandler).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
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
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.mismatch(
                newAsset.name(), "expected", "actual", "checksum"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateDoesNotUninstallWhenDownloadFails() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        Asset newAsset = new Asset("http://newurl", "example.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", oldAsset.url(), oldAsset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(newAsset), List.of(newAsset)));
        when(mockGithubClient.downloadAsset(newAsset)).thenThrow(new IOException("boom"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteUpdateAlreadyUpToDate() throws Exception {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", asset.url(), asset.name(), "/ProgramFiles/uninstall.exe")));
        when(mockGithubClient.getLatestRelease("user/example"))
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));

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
        when(mockCmdParser.getProvider()).thenReturn("github");
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
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockPlatformHandler.install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class))).thenReturn(null);

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
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.mismatch(
                asset.name(), "expected", "actual", "checksum"));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
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
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.missing(asset.name()));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
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
            .thenReturn(new ReleaseAssets(List.of(asset), List.of(asset)));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(any(), any(), any(), any()))
            .thenReturn(VerificationResult.missing(asset.name()));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockPlatformHandler, never()).install(any(cz.cuni.mff.releasemanager.platform.InstallRequest.class));
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

    private static String captureOut(IntSupplier action) {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            action.getAsInt();
        } finally {
            System.setOut(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void testExecuteDoctorWarnsWhenInstallRootIsNotOnPath() throws Exception {
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);
        when(mockStateStore.scanManifests()).thenReturn(List.of(
            manifest("user/example", "http://url", "example.exe", "/tmp/live")));

        String output = captureOut(() -> releaseManager.execute(new String[]{"doctor", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertTrue(root.get("warnings").toString().contains("PATH"),
            root.get("warnings").toString());
        assertTrue(root.get("healthy").asBoolean());
    }

    @Test
    void testExecuteDoctorReportsMissingSignatureTool() throws Exception {
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);
        when(mockSignatureVerifier.tools()).thenReturn(List.of(
            new SignatureVerifier.Tool("gpg", true, false),
            new SignatureVerifier.Tool("spctl", false, false)));

        String output = captureOut(() -> releaseManager.execute(new String[]{"doctor", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertFalse(root.get("healthy").asBoolean());
        String issues = root.get("issues").toString();
        assertTrue(issues.contains("gpg"), issues);
        assertTrue(issues.contains("Optional"), issues);
    }

    @Test
    void testExecuteDoctorReportsStaleMount() throws Exception {
        setOs(Platform.OS.MAC);
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        AppPaths paths = tempPaths();
        Path mount = Files.createDirectories(tempDir.resolve("Volumes"));
        Files.writeString(mount.resolve("leftover"), "data");
        when(mockPlatformHandler.mountPoint()).thenReturn(mount);

        String output = captureOut(() -> releaseManager.execute(new String[]{"doctor", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertFalse(root.get("healthy").asBoolean());
        assertTrue(root.get("issues").toString().contains("Stale mount"),
            root.get("issues").toString());
    }

    @Test
    void testExecuteDoctorReportsPinnedSigners() throws Exception {
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);
        when(mockStateStore.scanManifests()).thenReturn(List.of(
            withSignature(
                manifest("user/example", "http://url", "example.exe", "/tmp/live"),
                "FPR")));

        String output = captureOut(() -> releaseManager.execute(new String[]{"doctor", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals(1, root.get("pins").size());
        assertEquals("user/example", root.get("pins").get(0).get("repo").asText());
        assertEquals("FPR", root.get("pins").get(0).get("identity").asText());
        assertTrue(root.get("free_bytes").asLong() > 0);
    }

    @Test
    void testExecuteListJson() throws Exception {
        Command cmd = Command.LIST;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        when(mockStateStore.scanManifests()).thenReturn(List.of(
            manifest("user/example", "http://url", "example.exe", "/tmp/live")));

        String output = captureOut(() -> releaseManager.execute(new String[]{"list", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals(1, root.get("apps").size());
        JsonNode app = root.get("apps").get(0);
        assertEquals("github", app.get("provider").asText());
        assertEquals("user/example", app.get("repo").asText());
        assertEquals("1.0.0", app.get("version").asText());
        assertEquals("installed", app.get("install_state").asText());
    }

    @Test
    void testExecuteInfoJson() throws Exception {
        Command cmd = Command.INFO;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", "http://url", "example.exe", "/tmp/live")));

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"info", "user/example", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals("user/example", root.get("repo").asText());
        assertEquals("http://url", root.get("asset").get("url").asText());
        assertEquals("installed", root.get("install_state").asText());
        assertTrue(root.get("backups").isArray());
    }

    @Test
    void testExecuteSearchJson() throws Exception {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("github");
        when(mockCmdParser.isJson()).thenReturn(true);
        when(mockGithubClient.searchRepoByName("example")).thenReturn(Optional.of(
            new SearchResult(List.of(new Repo("user/example", "Example repo")))));

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"search", "example", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals(1, root.get("results").size());
        assertEquals("github", root.get("results").get(0).get("provider").asText());
        assertEquals("user/example", root.get("results").get(0).get("repo").asText());
        assertEquals("Example repo", root.get("results").get(0).get("description").asText());
    }

    @Test
    void testExecuteDoctorJson() throws Exception {
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);

        String output = captureOut(() -> releaseManager.execute(new String[]{"doctor", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertTrue(root.get("healthy").asBoolean());
        assertEquals(0, root.get("manifests").asInt());
        assertTrue(root.get("issues").isArray());
    }

    @Test
    void testExecuteProvidersListJson() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of("list");
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"providers", "list", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals(3, root.get("providers").size());
        assertEquals("codeberg", root.get("providers").get(0).get("name").asText());
        assertTrue(root.get("providers").get(0).get("built_in").asBoolean());
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
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(current));
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
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(current));
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
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(current));
        when(mockBackupManager.latest(current)).thenReturn(Optional.of(backup));
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"rollback", "user/example"}));

        verify(mockBackupManager, never()).restore(any(), any());
        verify(mockStateStore, never()).saveManifest(any());
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
    }

    @Test
    void testExecuteAdoptRefusedOutsideLinux() throws Exception {
        setOs(Platform.OS.MAC);
        Command cmd = Command.ADOPT;
        cmd.argument = "owner/repo";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(1, releaseManager.execute(new String[]{"adopt", "owner/repo"}));

        verify(mockStateStore, never()).findManifest(any(RepoRef.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteDoctorSkipsTrackedCustomProvider() throws Exception {
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            configWithProvider("work", "gitea", "https://git.example.com/api/v1"),
            false,
            null));
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.writeString(bin.resolve("owner__repo"), "data");
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);
        when(mockStateStore.scanManifests()).thenReturn(List.of(withProvider(
            manifest("owner/repo", "http://url", "example.exe", "/tmp/live"),
            "work")));

        assertEquals(0, releaseManager.execute(new String[]{"doctor"}));
    }

    @Test
    void testExecuteDoctorReportsUnconfiguredProvider() throws Exception {
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        tempPaths();
        when(mockStateStore.scanManifests()).thenReturn(List.of(withProvider(
            manifest("owner/repo", "http://url", "example.exe", "/tmp/live"),
            "work")));

        assertEquals(1, releaseManager.execute(new String[]{"doctor"}));
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
    void testExecuteDoctorReportsMissingLiveApp() throws Exception {
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        tempPaths();
        when(mockStateStore.scanManifests()).thenReturn(List.of(
            manifest("user/example", "http://url", "example.exe", "/tmp/live")));
        when(mockPlatformHandler.installState(any()))
            .thenReturn(PlatformHandler.InstallState.MISSING);

        assertEquals(1, releaseManager.execute(new String[]{"doctor"}));
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
        when(mockStateStore.findManifest(new RepoRef("github", "owner/repo"))).thenReturn(Optional.empty());
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
        when(mockStateStore.findManifest(new RepoRef("github", "owner/repo"))).thenReturn(Optional.empty());
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
    void testExecuteProvidersList() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of("list");
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(0, releaseManager.execute(new String[]{"providers", "list"}));
    }

    @Test
    void testExecuteProvidersAdd() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of(
            "add", "work", "https://git.example.com", "--type", "gitea");
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(0, releaseManager.execute(
            new String[]{"providers", "add", "work", "https://git.example.com"}));

        ArgumentCaptor<GrmConfig> captor = ArgumentCaptor.forClass(GrmConfig.class);
        verify(mockStateStore).saveConfig(captor.capture());
        ProviderConfig work = captor.getValue().providers().get("work");
        assertEquals("gitea", work.type());
        assertEquals("https://git.example.com/api/v1", work.resolvedApi());
    }

    @Test
    void testExecuteProvidersAddRejectsBuiltInName() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of(
            "add", "github", "https://github.com", "--type", "github");
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(1, releaseManager.execute(
            new String[]{"providers", "add", "github", "https://github.com"}));
        verify(mockStateStore, never()).saveConfig(any());
    }

    @Test
    void testExecuteProvidersRemoveRefusesWithPackages() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of("remove", "work");
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            configWithProvider("work", "gitea", "https://git.example.com/api/v1"),
            false,
            null));
        when(mockStateStore.scanManifests()).thenReturn(List.of(withProvider(
            manifest("owner/repo", "http://url", "example.exe", "/tmp/live"),
            "work")));

        assertEquals(1, releaseManager.execute(
            new String[]{"providers", "remove", "work"}));
        verify(mockStateStore, never()).saveConfig(any());
        verify(mockPlatformHandler, never()).uninstall(any());
    }

    @Test
    void testExecuteProvidersRemovePurge() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of("remove", "work", "--purge");
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            configWithProvider("work", "gitea", "https://git.example.com/api/v1"),
            false,
            null));
        AppManifest workManifest = withProvider(
            manifest("owner/repo", "http://url", "example.exe", "/tmp/live"),
            "work");
        when(mockStateStore.scanManifests()).thenReturn(List.of(workManifest));
        when(mockPlatformHandler.uninstall(Path.of("/tmp/live"))).thenReturn(true);
        when(mockStateStore.removeManifest(new RepoRef("work", "owner/repo")))
            .thenReturn(true);
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        assertEquals(0, releaseManager.execute(
            new String[]{"providers", "remove", "work", "--purge"}));

        ArgumentCaptor<GrmConfig> captor = ArgumentCaptor.forClass(GrmConfig.class);
        verify(mockStateStore).saveConfig(captor.capture());
        assertFalse(captor.getValue().providers().containsKey("work"));
        verify(mockPlatformHandler).uninstall(Path.of("/tmp/live"));
    }

    @Test
    void testExecuteProvidersRemoveAbandonLeavesPackages() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of("remove", "work", "--abandon");
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            configWithProvider("work", "gitea", "https://git.example.com/api/v1"),
            false,
            null));
        when(mockStateStore.scanManifests()).thenReturn(List.of(withProvider(
            manifest("owner/repo", "http://url", "example.exe", "/tmp/live"),
            "work")));
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        assertEquals(0, releaseManager.execute(
            new String[]{"providers", "remove", "work", "--abandon"}));

        verify(mockPlatformHandler, never()).uninstall(any());
        ArgumentCaptor<GrmConfig> captor = ArgumentCaptor.forClass(GrmConfig.class);
        verify(mockStateStore).saveConfig(captor.capture());
        assertFalse(captor.getValue().providers().containsKey("work"));
    }

    @Test
    void testExecuteProvidersUpdateRequiresForceWithPackages() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of(
            "update", "work", "--api", "https://new.example/api/v1");
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            configWithProvider("work", "gitea", "https://git.example.com/api/v1"),
            false,
            null));
        when(mockStateStore.scanManifests()).thenReturn(List.of(withProvider(
            manifest("owner/repo", "http://url", "example.exe", "/tmp/live"),
            "work")));

        assertEquals(1, releaseManager.execute(new String[]{"providers", "update", "work"}));
        verify(mockStateStore, never()).saveConfig(any());
    }

    @Test
    void testExecuteProvidersRecoverFromManifestMirror() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of("recover", "work");
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(
            new ConfigStore.LoadResult(GrmConfig.defaults(), false, null));
        when(mockStateStore.scanManifests()).thenReturn(List.of(withProviderMirror(
            manifest("owner/repo", "http://url", "example.exe", "/tmp/live"),
            "work", "gitea", "https://git.example.com/api/v1")));
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        assertEquals(0, releaseManager.execute(
            new String[]{"providers", "recover", "work"}));

        ArgumentCaptor<GrmConfig> captor = ArgumentCaptor.forClass(GrmConfig.class);
        verify(mockStateStore).saveConfig(captor.capture());
        ProviderConfig work = captor.getValue().providers().get("work");
        assertEquals("gitea", work.type());
        assertEquals("https://git.example.com", work.url());
        assertEquals("https://git.example.com/api/v1", work.resolvedApi());
    }

    @Test
    void testExecuteHelp() {
        Command cmd = Command.HELP;
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(0, releaseManager.execute(new String[]{"help"}));
        verify(mockCmdParser).parse(any());
    }
}