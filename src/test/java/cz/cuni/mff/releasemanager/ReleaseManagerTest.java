package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.ReleaseInfo;
import cz.cuni.mff.releasemanager.types.ReleasesList;
import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.SearchResult;
import cz.cuni.mff.releasemanager.verify.AssetVerifier;
import cz.cuni.mff.releasemanager.verify.VerificationResult;


class ReleaseManagerTest {

    private ReleaseManager releaseManager;
    private CmdParser mockCmdParser;
    private GithubClient mockGithubClient;
    private PlatformHandler mockPlatformHandler;
    private AssetVerifier mockVerifier;

    @BeforeEach
    void setUp() throws Exception {
        releaseManager = ReleaseManager.getInstance();

        mockCmdParser = mock(CmdParser.class);
        mockGithubClient = mock(GithubClient.class);
        mockPlatformHandler = mock(PlatformHandler.class);
        mockVerifier = mock(AssetVerifier.class);

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
        when(mockGithubClient.getLatestReleaseAssets("user/example")).thenReturn(List.of(asset));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.verified(asset.name(), "checksum"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/example.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).downloadAsset(asset);
        verify(mockVerifier).verify(asset, List.of(asset), downloaded);
        verify(mockGithubClient).installDownloadedAsset(downloaded, "user/example");
    }

    @Test
    void testExecuteUninstall() throws IOException {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        ReleaseInfo info = new ReleaseInfo("user/example", Instant.now(), "/ProgramFiles/uninstall.exe", asset);
        ReleasesList releases = new ReleasesList(List.of(info));
        Path uninstallPath = Path.of("/ProgramFiles/uninstall.exe");
        when(mockPlatformHandler.loadReleasesList()).thenReturn(releases);
        when(mockPlatformHandler.uninstall(uninstallPath)).thenReturn(true);
        when(mockPlatformHandler.removeReleaseFromList(info)).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"uninstall", "user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath);
        verify(mockPlatformHandler).removeReleaseFromList(info);
    }

    @Test
    void testExecuteUninstallDoesNotRemoveRecordWhenUninstallFails() throws IOException {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset asset = new Asset("http://url", "example.exe");
        ReleaseInfo info = new ReleaseInfo("user/example", Instant.now(), "/ProgramFiles/uninstall.exe", asset);
        Path uninstallPath = Path.of("/ProgramFiles/uninstall.exe");
        when(mockPlatformHandler.loadReleasesList()).thenReturn(new ReleasesList(List.of(info)));
        when(mockPlatformHandler.uninstall(uninstallPath)).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"uninstall", "user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath);
        verify(mockPlatformHandler, never()).removeReleaseFromList(any());
    }

    @Test
    void testExecuteUpdate() throws IOException, InterruptedException {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        ReleaseInfo info = new ReleaseInfo("user/example", Instant.now(), "/ProgramFiles/uninstall.exe", oldAsset);
        ReleasesList releases = new ReleasesList(List.of(info));
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");

        when(mockPlatformHandler.loadReleasesList()).thenReturn(releases);
        when(mockPlatformHandler.uninstall(Path.of("/ProgramFiles/uninstall.exe"))).thenReturn(true);
        when(mockGithubClient.getLatestReleaseAssets("user/example")).thenReturn(List.of(newAsset));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient).downloadAsset(newAsset);
        verify(mockGithubClient).installDownloadedAsset(downloaded, "user/example");
    }

    @Test
    void testExecuteUpdateDownloadsBeforeUninstalling() throws IOException, InterruptedException {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        ReleaseInfo info = new ReleaseInfo("user/example", Instant.now(), "/ProgramFiles/uninstall.exe", oldAsset);
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");

        when(mockPlatformHandler.loadReleasesList()).thenReturn(new ReleasesList(List.of(info)));
        when(mockPlatformHandler.uninstall(Path.of("/ProgramFiles/uninstall.exe"))).thenReturn(true);
        when(mockGithubClient.getLatestReleaseAssets("user/example")).thenReturn(List.of(newAsset));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockGithubClient.installDownloadedAsset(downloaded, "user/example"))
            .thenReturn(Path.of("/ProgramFiles/uninstall.exe"));

        assertEquals(0, releaseManager.execute(new String[]{"update", "user/example"}));

        InOrder inOrder = inOrder(mockGithubClient, mockVerifier, mockPlatformHandler);
        inOrder.verify(mockGithubClient).downloadAsset(newAsset);
        inOrder.verify(mockVerifier).verify(newAsset, List.of(newAsset), downloaded);
        inOrder.verify(mockPlatformHandler).uninstall(Path.of("/ProgramFiles/uninstall.exe"));
        inOrder.verify(mockGithubClient).installDownloadedAsset(downloaded, "user/example");
    }

    @Test
    void testExecuteUpdateAbortsWhenUninstallFails() throws IOException, InterruptedException {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        ReleaseInfo info = new ReleaseInfo("user/example", Instant.now(), "/ProgramFiles/uninstall.exe", oldAsset);
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");

        when(mockPlatformHandler.loadReleasesList()).thenReturn(new ReleasesList(List.of(info)));
        when(mockGithubClient.getLatestReleaseAssets("user/example")).thenReturn(List.of(newAsset));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.verified(newAsset.name(), "checksum"));
        when(mockPlatformHandler.uninstall(Path.of("/ProgramFiles/uninstall.exe"))).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
    }

    @Test
    void testExecuteUpdateFailsOnChecksumMismatch() throws IOException, InterruptedException {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        ReleaseInfo info = new ReleaseInfo("user/example", Instant.now(), "/ProgramFiles/uninstall.exe", oldAsset);
        Asset newAsset = new Asset("http://newurl", "example.exe");
        Path downloaded = Path.of("/tmp/downloaded.exe");

        when(mockPlatformHandler.loadReleasesList()).thenReturn(new ReleasesList(List.of(info)));
        when(mockGithubClient.getLatestReleaseAssets("user/example")).thenReturn(List.of(newAsset));
        when(mockGithubClient.downloadAsset(newAsset)).thenReturn(downloaded);
        when(mockVerifier.verify(newAsset, List.of(newAsset), downloaded))
            .thenReturn(VerificationResult.mismatch(
                newAsset.name(), "expected", "actual", "checksum"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
    }

    @Test
    void testExecuteUpdateDoesNotUninstallWhenDownloadFails() throws IOException, InterruptedException {
        Command cmd = Command.UPDATE;
        cmd.argument = "user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        Asset oldAsset = new Asset("http://oldurl", "example.exe");
        ReleaseInfo info = new ReleaseInfo("user/example", Instant.now(), "/ProgramFiles/uninstall.exe", oldAsset);
        Asset newAsset = new Asset("http://newurl", "example.exe");

        when(mockPlatformHandler.loadReleasesList()).thenReturn(new ReleasesList(List.of(info)));
        when(mockGithubClient.getLatestReleaseAssets("user/example")).thenReturn(List.of(newAsset));
        when(mockGithubClient.downloadAsset(newAsset)).thenThrow(new IOException("boom"));

        assertEquals(1, releaseManager.execute(new String[]{"update", "user/example"}));

        verify(mockPlatformHandler, never()).uninstall(any());
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
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
        when(mockGithubClient.getLatestReleaseAssets("user/example")).thenReturn(List.of(asset));
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
        when(mockGithubClient.getLatestReleaseAssets("user/example")).thenReturn(List.of(asset));
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
        when(mockGithubClient.getLatestReleaseAssets("user/example")).thenReturn(List.of(asset));
        when(mockGithubClient.downloadAsset(asset)).thenReturn(downloaded);
        when(mockVerifier.verify(asset, List.of(asset), downloaded))
            .thenReturn(VerificationResult.missing(asset.name()));

        assertEquals(1, releaseManager.execute(new String[]{"install", "user/example"}));

        verify(mockGithubClient).discardAsset(downloaded);
        verify(mockGithubClient, never()).installDownloadedAsset(any(), any());
    }

    @Test
    void testExecuteList() throws IOException {
        Command cmd = Command.LIST;
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        ReleaseInfo info = new ReleaseInfo("user/example", Instant.now(), "/ProgramFiles/uninstall.exe", null);
        ReleasesList releases = new ReleasesList(List.of(info));
        when(mockPlatformHandler.loadReleasesList()).thenReturn(releases);

        assertEquals(0, releaseManager.execute(new String[]{"list"}));
        verify(mockPlatformHandler).loadReleasesList();
    }

    @Test
    void testExecuteHelp() {
        Command cmd = Command.HELP;
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(0, releaseManager.execute(new String[]{"help"}));
        verify(mockCmdParser).parse(any());
    }
}