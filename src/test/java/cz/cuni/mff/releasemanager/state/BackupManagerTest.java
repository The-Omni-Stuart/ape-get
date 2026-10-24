package cz.cuni.mff.releasemanager.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupManagerTest {

    @TempDir
    Path tempDir;

    private AppManifest manifest(Path live, String kind, List<AppManifest.Backup> backups) {
        return new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            "github",
            "owner/repo",
            "1.0.0",
            1L,
            new AppManifest.Asset(live.getFileName().toString(), "https://example.com/file", null, null),
            new AppManifest.Install(
                kind,
                live.toString(),
                "/tmp/root",
                new AppManifest.Uninstall(live.toString(), List.of(), null)),
            new AppManifest.Verification("verified", "sha256", "file.sha256", "a", "a"),
            backups,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void backsUpAndRestoresFile() throws Exception {
        Path live = Files.writeString(tempDir.resolve("app.AppImage"), "old");
        AppPaths paths = new AppPaths(tempDir.resolve("state"));
        AppManifest manifest = manifest(live, "appimage", List.of());

        List<AppManifest.Backup> backups =
            new BackupManager(paths).createAndPrune(manifest, 1);

        assertEquals(1, backups.size());
        AppManifest.Backup backup = backups.get(0);
        assertTrue(Files.exists(Path.of(backup.artifact())));
        assertTrue(Files.exists(Path.of(backup.manifest())));
        assertEquals("old", Files.readString(Path.of(backup.artifact())));

        Files.writeString(live, "new");
        new BackupManager(paths).restore(backup, live);

        assertEquals("old", Files.readString(live));
        assertTrue(Files.exists(Path.of(backup.artifact())));
    }

    @Test
    void backsUpAndRestoresDirectory() throws Exception {
        Path live = Files.createDirectories(tempDir.resolve("App.app"));
        Files.writeString(live.resolve("old.txt"), "old");
        AppPaths paths = new AppPaths(tempDir.resolve("state"));
        AppManifest manifest = manifest(live, "dmg", List.of());

        List<AppManifest.Backup> backups =
            new BackupManager(paths).createAndPrune(manifest, 1);

        AppManifest.Backup backup = backups.get(0);
        Files.writeString(live.resolve("new.txt"), "new");
        new BackupManager(paths).restore(backup, live);

        assertEquals("old", Files.readString(live.resolve("old.txt")));
        assertFalse(Files.exists(live.resolve("new.txt")));
    }

    @Test
    void prunesOldBackups() throws Exception {
        Path live = Files.writeString(tempDir.resolve("app.AppImage"), "first");
        AppPaths paths = new AppPaths(tempDir.resolve("state"));
        BackupManager manager = new BackupManager(paths);

        List<AppManifest.Backup> first = manager.createAndPrune(
            manifest(live, "appimage", List.of()), 1);
        Files.writeString(live, "second");
        List<AppManifest.Backup> second = manager.createAndPrune(
            manifest(live, "appimage", first), 1);

        assertEquals(1, second.size());
        assertFalse(Files.exists(Path.of(first.get(0).path())));
        assertEquals("second", Files.readString(Path.of(second.get(0).artifact())));
    }

    @Test
    void failsWhenLivePathIsMissing() {
        Path live = tempDir.resolve("missing.AppImage");
        AppPaths paths = new AppPaths(tempDir.resolve("state"));

        assertThrows(IOException.class,
            () -> new BackupManager(paths).createAndPrune(
                manifest(live, "appimage", List.of()), 1));
    }
}
