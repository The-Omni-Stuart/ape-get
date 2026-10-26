package cz.cuni.mff.releasemanager;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.types.RepoRef;


class ReleaseManagerProvidersTest extends ReleaseManagerTestSupport {

    @Test
    void testExecuteProvidersAddJson() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of(
            "add", "work", "https://git.example.com",
            "--type", "gitea", "--api", "https://git.example.com/api/v1");
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);

        String output = captureOut(() -> releaseManager.execute(new String[]{
            "providers", "add", "work", "https://git.example.com", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals("work", root.get("provider").get("name").asText());
        assertEquals("gitea", root.get("provider").get("type").asText());
        verify(mockStateStore).saveConfig(any());
    }


    @Test
    void testExecuteRejectsAnUnsupportedProxy() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of("list");
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            new GrmConfig(
                GrmConfig.KIND, GrmConfig.SCHEMA_VERSION,
                false, 1, null, false, java.util.Map.of(), true,
                "stable", "off", 24, 30, "socks5://127.0.0.1:9050"),
            false,
            null));

        String error = captureErr(() -> releaseManager.execute(
            new String[]{"providers", "list"}));

        assertTrue(error.contains("Invalid network config"), error);
        assertTrue(error.contains("HTTPTunnelPort"), error);
    }

    @Test
    void testExecuteProvidersAddRngit() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of(
            "add", "rns", "rns://7649a50d84610232d1416b41d2896aff", "--type", "rngit");
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(0, releaseManager.execute(new String[]{
            "providers", "add", "rns",
            "rns://7649a50d84610232d1416b41d2896aff", "--type", "rngit"}));

        ArgumentCaptor<GrmConfig> captor = ArgumentCaptor.forClass(GrmConfig.class);
        verify(mockStateStore).saveConfig(captor.capture());
        ProviderConfig rns = captor.getValue().providers().get("rns");
        assertEquals("rngit", rns.type());
        assertEquals("rns://7649a50d84610232d1416b41d2896aff", rns.resolvedApi());
    }

    @Test
    void testExecuteProvidersAddDerivesAnRngitName() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of("add", "rns://7649a50d84610232d1416b41d2896aff");
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(0, releaseManager.execute(new String[]{
            "providers", "add", "rns://7649a50d84610232d1416b41d2896aff"}));

        ArgumentCaptor<GrmConfig> captor = ArgumentCaptor.forClass(GrmConfig.class);
        verify(mockStateStore).saveConfig(captor.capture());
        assertEquals("rngit",
            captor.getValue().providers().get("rns-7649a50d").type());
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
    void testExecuteProvidersUpdateKeepsTheCustomApi() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of("update", "work");
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            configWithProvider("work", "gitea", "https://git.example.com/api/custom"),
            false,
            null));

        assertEquals(0, releaseManager.execute(
            new String[]{"providers", "update", "work"}));

        ArgumentCaptor<GrmConfig> captor = ArgumentCaptor.forClass(GrmConfig.class);
        verify(mockStateStore).saveConfig(captor.capture());
        assertEquals("https://git.example.com/api/custom",
            captor.getValue().providers().get("work").api());
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
}
