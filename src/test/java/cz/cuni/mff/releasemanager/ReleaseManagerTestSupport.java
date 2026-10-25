package cz.cuni.mff.releasemanager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Prompt;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.GithubClient;
import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.ProviderFactory;
import cz.cuni.mff.releasemanager.provider.SearchService;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.GrmIndex;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.verify.AssetVerifier;
import cz.cuni.mff.releasemanager.verify.SignatureResult;
import cz.cuni.mff.releasemanager.verify.SignatureVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

abstract class ReleaseManagerTestSupport {


    @TempDir
    Path tempDir;

    protected ReleaseManager releaseManager;
    protected CmdParser mockCmdParser;
    protected GithubClient mockGithubClient;
    protected PlatformHandler mockPlatformHandler;
    protected AssetVerifier mockVerifier;
    protected SignatureVerifier mockSignatureVerifier;
    protected StateStore mockStateStore;
    protected BackupManager mockBackupManager;
    protected AppPaths mockPaths;
    protected Prompt mockPrompt;

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

        releaseManager = new ReleaseManager(
            mockCmdParser, new ProviderFactory(mockGithubClient), mockPlatformHandler, mockVerifier,
            mockSignatureVerifier, mockStateStore, mockBackupManager, mockPrompt,
            new SearchService(), Platform.OS.LINUX);

        when(mockStateStore.paths()).thenReturn(mockPaths);
        when(mockBackupManager.createAndPrune(any(), anyInt())).thenReturn(List.of());
        when(mockSignatureVerifier.verify(any(), any(), any(), any())).thenReturn(
            SignatureResult.verified("example.exe", "gpg", "Test Signer", "FPR"));
        when(mockStateStore.loadConfig()).thenReturn(
            new ConfigStore.LoadResult(GrmConfig.defaults(), false, null));
        when(mockStateStore.scanManifests()).thenReturn(List.of());
        when(mockStateStore.loadIndex()).thenReturn(Optional.of(new GrmIndex(
            GrmIndex.KIND, GrmIndex.SCHEMA_VERSION, Instant.now(), List.of())));
        when(mockStateStore.findRecoveryIssues(any(), any())).thenReturn(List.of());
        when(mockPlatformHandler.installState(any()))
            .thenReturn(PlatformHandler.InstallState.INSTALLED);
    }

    protected void setOs(Platform.OS os) {
        releaseManager.setOs(os);
    }

    protected void useSearchService(SearchService service) {
        releaseManager = new ReleaseManager(
            mockCmdParser, new ProviderFactory(mockGithubClient), mockPlatformHandler, mockVerifier,
            mockSignatureVerifier, mockStateStore, mockBackupManager, mockPrompt,
            service, Platform.OS.LINUX);
    }

    protected static AppManifest manifest(
        String repo, String assetUrl, String assetName, String uninstallPath) {
        return new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            "github",
            repo,
            "1.0.0",
            1L,
            new AppManifest.Asset(assetName, assetUrl, null),
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

    protected static AppManifest withSignature(AppManifest base, String identity) {
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

    protected static AppManifest withProductCode(AppManifest base, String productCode) {
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

    protected static AppManifest withProvider(AppManifest base, String provider) {
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

    protected static AppManifest withProviderMirror(
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

    protected static GrmConfig configWithProvider(String name, String type, String api) {
        return new GrmConfig(
            GrmConfig.KIND,
            GrmConfig.SCHEMA_VERSION,
            false,
            1,
            null,
            false,
            Map.of(name, new ProviderConfig(type, "https://git.example.com", api, null)));
    }

protected static String captureOut(IntSupplier action) {
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

protected static String captureErr(IntSupplier action) {
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            action.getAsInt();
        } finally {
            System.setErr(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

protected static AppManifest.Backup backup(String version) {
        return new AppManifest.Backup(
     version,
     "/backup/" + version,
     "/backup/" + version + "/artifact",
     "/tmp/live",
     Instant.parse("2026-01-01T00:00:00Z"),
     "/backup/" + version + "/grm_backup_manifest.json");
    }

protected AppPaths tempPaths() throws IOException {
        AppPaths paths = new AppPaths(tempDir);
        when(mockStateStore.paths()).thenReturn(paths);
        Files.createDirectories(paths.root());
        Files.createFile(paths.configFile());
        return paths;
    }
}
