package cz.cuni.mff.releasemanager.state;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateStoreTest {

    @TempDir
    Path tempDir;

    private StateStore store() {
        return new StateStore(new AppPaths(tempDir));
    }

    private static AppManifest manifest(String repo) {
        return new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            "github",
            repo,
            "1.0.0",
            1L,
            new AppManifest.Asset("file.AppImage", "https://example.com/file", 123L, "abc"),
            new AppManifest.Install(
                "appimage",
                "/tmp/bin/" + repo,
                "/tmp/root/" + repo,
                new AppManifest.Uninstall("/tmp/bin/" + repo, List.of(), null)),
            new AppManifest.Verification("verified", "sha256", "file.sha256", "abc", "abc"),
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void savesAndScansManifests() throws Exception {
        StateStore store = store();
        store.saveManifest(manifest("owner/repo"));

        List<AppManifest> manifests = store.scanManifests();

        assertEquals(1, manifests.size());
        assertEquals("owner/repo", manifests.get(0).repo());
        Path manifestFile = new AppPaths(tempDir).manifestFile("github", "owner/repo");
        assertTrue(Files.exists(manifestFile));
        assertFalse(Files.readString(manifestFile).contains("\"valid\""));
    }

    @Test
    void findsAndRemovesManifest() throws Exception {
        StateStore store = store();
        store.saveManifest(manifest("owner/repo"));

        assertTrue(store.findManifest("owner/repo").isPresent());
        assertTrue(store.removeManifest("owner/repo"));
        assertTrue(store.findManifest("owner/repo").isEmpty());
        assertTrue(store.scanManifests().isEmpty());
    }

    @Test
    void ignoresForeignManifestKind() throws Exception {
        AppPaths paths = new AppPaths(tempDir);
        Path file = paths.manifestFile("github", "owner/repo");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            { "kind": "other", "schema_version": 1 }
            """);

        StateStore store = new StateStore(paths);

        assertTrue(store.scanManifests().isEmpty());
        assertTrue(Files.exists(file));
    }

    @Test
    void quarantinesCorruptManifest() throws Exception {
        AppPaths paths = new AppPaths(tempDir);
        Path file = paths.manifestFile("github", "owner/repo");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "garbage");

        StateStore store = new StateStore(paths);

        assertTrue(store.scanManifests().isEmpty());
        assertFalse(Files.exists(file));
        try (var files = Files.list(file.getParent())) {
            assertTrue(files.anyMatch(path ->
                path.getFileName().toString().startsWith("grm_manifest.json.corrupt-")));
        }
    }

    @Test
    void ignoresManifestCopiesInsideBackups() throws Exception {
        StateStore store = store();
        AppPaths paths = new AppPaths(tempDir);
        store.saveManifest(manifest("owner/repo"));
        Path backup = paths.appDir("github", "owner/repo")
            .resolve("backups")
            .resolve("grm_manifest.json");
        Files.createDirectories(backup.getParent());
        Files.writeString(backup, "{}");

        assertEquals(1, store.scanManifests().size());
    }

    @Test
    void rebuildsIndexFromManifests() throws Exception {
        StateStore store = store();
        AppPaths paths = new AppPaths(tempDir);
        store.saveManifest(manifest("owner/repo"));

        GrmIndex index = store.rebuildIndex();

        assertEquals(1, index.apps().size());
        GrmIndex.Entry entry = index.apps().get(0);
        assertEquals("owner/repo", entry.repo());
        assertEquals("applications/github/owner__repo/grm_manifest.json", entry.manifest());
        assertEquals("owner/repo", entry.snapshot().repo());
        assertTrue(Files.exists(paths.indexFile()));
        String json = Files.readString(paths.indexFile());
        assertTrue(json.contains("\"kind\" : \"grm.index\""));
        assertTrue(json.contains("2026-01-01T00:00:00Z"));
        assertFalse(json.contains("\"valid\""));
    }

    @Test
    void recoveryRestoresMissingManifestWhenLiveAppExists() throws Exception {
        StateStore store = store();
        AppManifest snapshot = manifest("owner/repo");
        Path live = Files.createFile(tempDir.resolve("live.AppImage"));
        GrmIndex.Entry entry = new GrmIndex.Entry(
            "owner/repo",
            snapshot.version(),
            snapshot.installedAt(),
            live.toString(),
            "applications/github/owner__repo/grm_manifest.json",
            snapshot);

        List<StateStore.RecoveryIssue> issues = store.findRecoveryIssues(
            new GrmIndex(GrmIndex.KIND, GrmIndex.SCHEMA_VERSION, Instant.now(), List.of(entry)),
            List.of());

        assertEquals(1, issues.size());
        assertEquals(StateStore.RecoveryKind.RESTORE_MANIFEST, issues.get(0).kind());
        assertEquals(entry, issues.get(0).entry());
    }

    @Test
    void recoveryDropsMissingManifestWhenLiveAppIsGone() throws Exception {
        StateStore store = store();
        AppManifest snapshot = manifest("owner/repo");
        GrmIndex.Entry entry = new GrmIndex.Entry(
            "owner/repo",
            snapshot.version(),
            snapshot.installedAt(),
            tempDir.resolve("missing.AppImage").toString(),
            "applications/github/owner__repo/grm_manifest.json",
            snapshot);

        List<StateStore.RecoveryIssue> issues = store.findRecoveryIssues(
            new GrmIndex(GrmIndex.KIND, GrmIndex.SCHEMA_VERSION, Instant.now(), List.of(entry)),
            List.of());

        assertEquals(1, issues.size());
        assertEquals(StateStore.RecoveryKind.DROP_INDEX_ENTRY, issues.get(0).kind());
    }

    @Test
    void recoveryIgnoresEntriesWithManifest() throws Exception {
        StateStore store = store();
        store.saveManifest(manifest("owner/repo"));
        AppManifest snapshot = manifest("owner/repo");
        GrmIndex.Entry entry = new GrmIndex.Entry(
            "owner/repo",
            snapshot.version(),
            snapshot.installedAt(),
            tempDir.resolve("live.AppImage").toString(),
            "applications/github/owner__repo/grm_manifest.json",
            snapshot);

        List<StateStore.RecoveryIssue> issues = store.findRecoveryIssues(
            new GrmIndex(GrmIndex.KIND, GrmIndex.SCHEMA_VERSION, Instant.now(), List.of(entry)),
            store.scanManifests());

        assertTrue(issues.isEmpty());
    }

    @Test
    void recoveryDropsEntryWithoutSnapshot() throws Exception {
        StateStore store = store();
        Path live = Files.createFile(tempDir.resolve("live.AppImage"));
        GrmIndex.Entry entry = new GrmIndex.Entry(
            "owner/repo",
            "1.0.0",
            Instant.now(),
            live.toString(),
            "applications/github/owner__repo/grm_manifest.json",
            null);

        List<StateStore.RecoveryIssue> issues = store.findRecoveryIssues(
            new GrmIndex(GrmIndex.KIND, GrmIndex.SCHEMA_VERSION, Instant.now(), List.of(entry)),
            List.of());

        assertEquals(StateStore.RecoveryKind.DROP_INDEX_ENTRY, issues.get(0).kind());
    }

    @Test
    void loadsIndexAfterRebuild() throws Exception {
        StateStore store = store();
        store.saveManifest(manifest("owner/repo"));
        store.rebuildIndex();

        assertTrue(store.loadIndex().isPresent());
    }

    @Test
    void quarantinesCorruptIndex() throws Exception {
        StateStore store = store();
        AppPaths paths = new AppPaths(tempDir);
        Files.createDirectories(paths.root());
        Files.writeString(paths.indexFile(), "garbage");

        assertTrue(store.loadIndex().isEmpty());
        try (var files = Files.list(paths.root())) {
            assertTrue(files.anyMatch(path ->
                path.getFileName().toString().startsWith("grm_index.json.corrupt-")));
        }
    }

    @Test
    void leavesNoTemporaryManifestFiles() throws Exception {
        StateStore store = store();
        AppPaths paths = new AppPaths(tempDir);
        store.saveManifest(manifest("owner/repo"));

        try (var files = Files.list(paths.appDir("github", "owner/repo"))) {
            assertEquals(1, files.count());
        }
    }
}
