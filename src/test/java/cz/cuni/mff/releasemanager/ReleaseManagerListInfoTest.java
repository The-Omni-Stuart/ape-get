package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.GrmIndex;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.RepoRef;


class ReleaseManagerListInfoTest extends ReleaseManagerTestSupport {

    @Test
    void testExecuteInfo() throws Exception {
        Command cmd = Command.INFO;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", "http://url", "example.exe", "/tmp/live")));

        assertEquals(0, releaseManager.execute(new String[]{"info", "github:user/example"}));

        verify(mockStateStore).findManifest(new RepoRef("github", "user/example"));
    }

    @Test
    void testExecuteInfoMissing() throws Exception {
        Command cmd = Command.INFO;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.findManifest(new RepoRef("github", "user/example"))).thenReturn(Optional.empty());

        assertEquals(1, releaseManager.execute(new String[]{"info", "github:user/example"}));
    }

    @Test
    void testExecuteUsageReturnsUsageCode() {
        Command cmd = Command.USAGE;
        cmd.argument = null;
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(2, releaseManager.execute(new String[]{"bad"}));
    }

    @Test
    void testExecuteListRestoresMissingManifestWhenConfirmed() throws Exception {
        Command cmd = Command.LIST;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest snapshot = manifest(
            "owner/repo", "http://url", "example.exe", "/tmp/live");
        GrmIndex.Entry entry = new GrmIndex.Entry(
     snapshot.repo(),
     "/tmp/live",
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
     "/tmp/live",
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
     "owner/repo",
     "/tmp/missing",
     null);
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
        assertEquals("example.exe", app.get("asset").asText());
    }

    @Test
    void testExecuteInfoJson() throws Exception {
        Command cmd = Command.INFO;
        cmd.argument = "github:user/example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.isJson()).thenReturn(true);
        when(mockStateStore.findManifest(new RepoRef("github", "user/example")))
            .thenReturn(Optional.of(manifest(
                "user/example", "http://url", "example.exe", "/tmp/live")));

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"info", "github:user/example", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals("user/example", root.get("repo").asText());
        assertEquals("http://url", root.get("asset").get("url").asText());
        assertEquals("installed", root.get("install_state").asText());
        assertTrue(root.get("backups").isArray());
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

    @Test
    void testExecuteListRebuildsIndexWhenAnotherProviderSharesRepoName() throws Exception {
        Command cmd = Command.LIST;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        AppManifest github = manifest(
            "user/example", "http://url", "example.exe", "/tmp/live");
        GrmIndex.Entry entry = new GrmIndex.Entry(
     github.repo(),
     github.install().path(),
     github,
     "github");
        when(mockStateStore.loadIndex()).thenReturn(Optional.of(new GrmIndex(
            GrmIndex.KIND, GrmIndex.SCHEMA_VERSION, Instant.now(), List.of(entry))));
        when(mockStateStore.scanManifests())
            .thenReturn(List.of(github, withProvider(github, "gitlab")));

        assertEquals(0, releaseManager.execute(new String[]{"list"}));

        verify(mockStateStore).rebuildIndex();
    }

    @Test
    void testExecuteListShowsProvider() throws IOException {
        Command cmd = Command.LIST;
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.scanManifests()).thenReturn(List.of(
            manifest("user/example", "http://url", "example.exe", "/ProgramFiles/uninstall.exe")));

        String output = captureOut(() -> releaseManager.execute(new String[]{"list"}));

        assertTrue(output.contains("github:user/example 1.0.0"));
    }

    @Test
    void testExecuteHelp() {
        Command cmd = Command.HELP;
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        assertEquals(0, releaseManager.execute(new String[]{"help"}));
        verify(mockCmdParser).parse(any());
    }
}
