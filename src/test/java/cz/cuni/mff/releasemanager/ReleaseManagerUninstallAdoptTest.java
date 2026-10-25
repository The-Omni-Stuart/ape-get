package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.types.RepoRef;


class ReleaseManagerUninstallAdoptTest extends ReleaseManagerTestSupport {

    @Test
    void testExecuteUninstallJson() throws Exception {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        AppManifest manifest = manifest(
            "user/example", "http://url", "example.exe", "/ProgramFiles/uninstall.exe");
        Path uninstallPath = Path.of("/ProgramFiles/uninstall.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest));
        when(mockPlatformHandler.uninstall(uninstallPath)).thenReturn(true);
        when(mockStateStore.removeManifest(
            new RepoRef("github", "user/example"))).thenReturn(true);

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"uninstall", "github:user/example", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals("user/example", root.get("uninstalled").get("repo").asText());
        assertEquals("/ProgramFiles/uninstall.exe",
            root.get("uninstalled").get("path").asText());
    }

    @Test
    void testExecuteAdoptStoresProvider() throws Exception {
        Command cmd = Command.ADOPT;
        cmd.argument = "work:owner/repo";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.writeString(bin.resolve("work__owner__repo"), "data");
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
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        AppManifest manifest = manifest(
            "user/example", "http://url", "example.exe", "/ProgramFiles/uninstall.exe");
        Path uninstallPath = Path.of("/ProgramFiles/uninstall.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(manifest));
        when(mockPlatformHandler.uninstall(uninstallPath)).thenReturn(true);
        when(mockStateStore.removeManifest(new RepoRef("github", "user/example"))).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"uninstall", "github:user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath);
        verify(mockStateStore).removeManifest(new RepoRef("github", "user/example"));
        verify(mockStateStore).rebuildIndex();
    }

    @Test
    void testExecuteUninstallCanBeDeclined() throws IOException {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        AppManifest manifest = manifest(
            "user/example", "http://url", "example.exe", "/ProgramFiles/uninstall.exe");
        Path uninstallPath = Path.of("/ProgramFiles/uninstall.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest));
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(false);

        int[] code = new int[1];
        String error = captureErr(() -> code[0] = releaseManager.execute(
            new String[]{"uninstall", "github:user/example"}));

        assertEquals(1, code[0]);
        assertTrue(error.contains("Uninstall declined"), error);
        verify(mockPlatformHandler, never()).uninstall(any(Path.class));
        verify(mockStateStore, never()).removeManifest(any(RepoRef.class));
    }

    @Test
    void testExecuteUninstallDoesNotRemoveRecordWhenUninstallFails() throws IOException {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        AppManifest manifest = manifest(
            "user/example", "http://url", "example.exe", "/ProgramFiles/uninstall.exe");
        Path uninstallPath = Path.of("/ProgramFiles/uninstall.exe");
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.of(manifest));
        when(mockPlatformHandler.uninstall(uninstallPath)).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"uninstall", "github:user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath);
        verify(mockStateStore, never()).removeManifest(any(RepoRef.class));
    }

    @Test
    void testExecuteUninstallUsesProductCode() throws Exception {
        Command cmd = Command.UNINSTALL;
        cmd.argument = "github:user/example";
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

        assertEquals(0, releaseManager.execute(new String[]{"uninstall", "github:user/example"}));

        verify(mockPlatformHandler).uninstall(uninstallPath, productCode);
        verify(mockPlatformHandler, never()).uninstall(uninstallPath);
        verify(mockStateStore).removeManifest(new RepoRef("github", "user/example"));
    }

    @Test
    void testExecuteAdoptRefusedOutsideLinux() throws Exception {
        setOs(Platform.OS.MAC);
        Command cmd = Command.ADOPT;
        cmd.argument = "github:owner/repo";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(1, releaseManager.execute(new String[]{"adopt", "github:owner/repo"}));

        verify(mockStateStore, never()).findManifest(any(RepoRef.class));
        verify(mockStateStore, never()).saveManifest(any());
    }

    @Test
    void testExecuteAdoptWritesManifest() throws Exception {
        Command cmd = Command.ADOPT;
        cmd.argument = "github:owner/repo";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.writeString(bin.resolve("github__owner__repo"), "data");
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);
        when(mockStateStore.findManifest(new RepoRef("github", "owner/repo"))).thenReturn(Optional.empty());
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(true);

        assertEquals(0, releaseManager.execute(new String[]{"adopt", "github:owner/repo"}));

        verify(mockStateStore).saveManifest(any());
        verify(mockStateStore).rebuildIndex();
    }

    @Test
    void testExecuteAdoptCanBeDeclined() throws Exception {
        Command cmd = Command.ADOPT;
        cmd.argument = "github:owner/repo";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppPaths paths = tempPaths();
        Path bin = Files.createDirectories(paths.root().resolve("bin"));
        Files.writeString(bin.resolve("github__owner__repo"), "data");
        when(mockPlatformHandler.getInstallRoot()).thenReturn(bin);
        when(mockStateStore.findManifest(new RepoRef("github", "owner/repo"))).thenReturn(Optional.empty());
        when(mockPrompt.confirm(any(), anyBoolean())).thenReturn(false);

        assertEquals(1, releaseManager.execute(new String[]{"adopt", "github:owner/repo"}));

        verify(mockStateStore, never()).saveManifest(any());
    }
}
