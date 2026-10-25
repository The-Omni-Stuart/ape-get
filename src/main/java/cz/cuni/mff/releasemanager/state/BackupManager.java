package cz.cuni.mff.releasemanager.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import cz.cuni.mff.releasemanager.utils.FileUtils;

// versioned backups for installed apps
public final class BackupManager {
    private final AppPaths paths;

    public BackupManager(AppPaths paths) {
        this.paths = paths;
    }

    public AppManifest.Backup create(AppManifest manifest) throws IOException {
        Path live = Path.of(manifest.install().path());
        if (!Files.exists(live)) {
            throw new IOException("Installed path does not exist: " + live);
        }

        Path backupDir = paths.appDir(manifest.provider(), manifest.repo())
            .resolve("backups")
            .resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID());
        Files.createDirectories(backupDir);
        try {
            Path artifact = backupDir.resolve(live.getFileName());
            if (Files.isDirectory(live)) {
                FileUtils.copyRecursively(live, artifact);
            } else {
                Files.copy(live, artifact, StandardCopyOption.COPY_ATTRIBUTES);
            }

            Path backupManifest = backupDir.resolve("grm_backup_manifest.json");
            StateJson.write(backupManifest, manifest);

            return new AppManifest.Backup(
                manifest.version(),
                backupDir.toString(),
                artifact.toString(),
                live.toString(),
                Instant.now(),
                backupManifest.toString());
        } catch (IOException | RuntimeException e) {
            FileUtils.deleteRecursively(backupDir);
            throw e;
        }
    }

    // create a backup, then drop the oldest until keep remain
    public List<AppManifest.Backup> createAndPrune(AppManifest manifest, int keep)
        throws IOException {
        List<AppManifest.Backup> current = manifest.backups() == null
            ? List.of()
            : manifest.backups();
        if (keep <= 0) {
            return current;
        }

        List<AppManifest.Backup> backups = new ArrayList<>(current);
        backups.add(create(manifest));
        backups.sort(Comparator.comparing(AppManifest.Backup::createdAt));
        while (backups.size() > keep) {
            delete(backups.remove(0));
        }
        return List.copyOf(backups);
    }

    public Optional<AppManifest.Backup> latest(AppManifest manifest) {
        if (manifest.backups() == null || manifest.backups().isEmpty()) {
            return Optional.empty();
        }
        return manifest.backups().stream()
            .max(Comparator.comparing(AppManifest.Backup::createdAt));
    }

    public AppManifest readManifest(AppManifest.Backup backup) throws IOException {
        return StateJson.MAPPER.readValue(Path.of(backup.manifest()).toFile(), AppManifest.class);
    }

    public void restore(AppManifest.Backup backup, Path live) throws IOException {
        Path artifact = Path.of(backup.artifact());
        if (!Files.exists(artifact)) {
            throw new IOException("Backup artefact is missing: " + artifact);
        }
        Path parent = live.toAbsolutePath().getParent();
        Files.createDirectories(parent);

        if (Files.isDirectory(artifact)) {
            Path staging = Files.createTempDirectory(parent, ".ape-get-restore-");
            try {
                Path staged = staging.resolve(artifact.getFileName());
                FileUtils.copyRecursively(artifact, staged);
                FileUtils.replaceDirectory(staged, live);
            } finally {
                FileUtils.deleteRecursively(staging);
            }
        } else {
            Path temp = Files.createTempFile(parent, ".ape-get-restore-", ".tmp");
            try {
                Files.copy(artifact, temp,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                FileUtils.replaceFile(temp, live);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private void delete(AppManifest.Backup backup) throws IOException {
        Path dir = Path.of(backup.path());
        if (Files.exists(dir)) {
            FileUtils.deleteRecursively(dir);
        }
    }
}
