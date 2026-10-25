package cz.cuni.mff.releasemanager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.verify.SignatureVerifier;


class ReleaseManagerDoctorTest extends ReleaseManagerTestSupport {

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
    void testExecuteDoctorSkipsOrphansOutsideLinux() throws Exception {
        setOs(Platform.OS.MAC);
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.writeString(bin.resolve("work__owner__repo"), "data");
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);

        assertEquals(0, releaseManager.execute(new String[]{"doctor"}));
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
        Files.writeString(bin.resolve("work__owner__repo"), "data");
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
        Files.writeString(bin.resolve("work__owner__repo"), "data");
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);

        assertEquals(1, releaseManager.execute(new String[]{"doctor"}));
    }

    @Test
    void testExecuteDoctorFindsBrokenLauncherSymlink() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            !System.getProperty("os.name").toLowerCase().contains("win"));
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.createSymbolicLink(bin.resolve("work__owner__repo"), bin.resolve("missing"));
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);

        assertEquals(1, releaseManager.execute(new String[]{"doctor"}));
    }

    @Test
    void testExecuteDoctorReportsTrackedBrokenLauncher() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            !System.getProperty("os.name").toLowerCase().contains("win"));
        Command cmd = Command.DOCTOR;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Path live = Files.writeString(paths.root().resolve("live"), "data");
        Files.createSymbolicLink(
            bin.resolve("github__user__example"), paths.root().resolve("missing"));
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);
        when(mockStateStore.scanManifests()).thenReturn(List.of(
            manifest("user/example", "http://url", "example.exe", live.toString())));

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"doctor"}));

        assertTrue(output.contains("Broken launcher"), output);
    }
}
