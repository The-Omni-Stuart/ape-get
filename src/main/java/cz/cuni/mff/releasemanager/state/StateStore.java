package cz.cuni.mff.releasemanager.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;

import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;

// manifests are the source of truth; the index is rebuilt from them
public final class StateStore {
    public enum RecoveryKind {
        RESTORE_MANIFEST,
        DROP_INDEX_ENTRY
    }

    public record RecoveryIssue(RecoveryKind kind, GrmIndex.Entry entry) {}

    private final AppPaths paths;
    private final ConfigStore configStore;

    public StateStore(AppPaths paths) {
        this.paths = paths;
        this.configStore = new ConfigStore(paths);
    }

    public AppPaths paths() {
        return paths;
    }

    public ConfigStore.LoadResult loadConfig() throws IOException {
        return configStore.load();
    }

    // persist tool config atomically
    public void saveConfig(GrmConfig config) throws IOException {
        configStore.save(config);
    }

    // all valid manifests; foreign kinds are ignored, corrupt files quarantined
    public List<AppManifest> scanManifests() throws IOException {
        Path appsDir = paths.applicationsDir();
        if (!Files.exists(appsDir)) {
            return List.of();
        }
        List<AppManifest> manifests = new ArrayList<>();
        try (var files = Files.walk(appsDir)) {
            for (Path file : files.filter(this::isManifestFile).toList()) {
                readManifest(file).ifPresent(manifests::add);
            }
        }
        manifests.sort(Comparator.comparing(AppManifest::repo));
        return manifests;
    }

    public Optional<AppManifest> findManifest(RepoRef ref) throws IOException {
        return scanManifests().stream()
            .filter(manifest -> ref.provider().equals(manifest.provider())
                && ref.path().equals(manifest.repo()))
            .findFirst();
    }

    // index entries with no matching manifest, or with a snapshot worth restoring
    public List<RecoveryIssue> findRecoveryIssues(
        GrmIndex index, List<AppManifest> manifests) {
        if (index == null || index.apps() == null) {
            return List.of();
        }
        Set<String> repos = new HashSet<>();
        for (AppManifest manifest : manifests) {
            if (manifest.repo() != null) {
                repos.add(manifestKey(manifest.provider(), manifest.repo()));
            }
        }

        List<RecoveryIssue> issues = new ArrayList<>();
        for (GrmIndex.Entry entry : index.apps()) {
            if (entry == null || entry.repo() == null) {
                if (entry != null) {
                    issues.add(new RecoveryIssue(RecoveryKind.DROP_INDEX_ENTRY, entry));
                }
                continue;
            }
            String provider = entry.provider();
            if (provider == null && entry.snapshot() != null) {
                provider = entry.snapshot().provider();
            }
            if (repos.contains(manifestKey(provider, entry.repo()))) {
                continue;
            }

            Path live = null;
            if (entry.installPath() != null) {
                try {
                    live = Path.of(entry.installPath());
                } catch (InvalidPathException ignored) {
                    // treated as missing
                }
            }
            boolean restorable = live != null
                && Files.exists(live)
                && entry.snapshot() != null
                && entry.snapshot().isValid();
            issues.add(new RecoveryIssue(
                restorable ? RecoveryKind.RESTORE_MANIFEST : RecoveryKind.DROP_INDEX_ENTRY,
                entry));
        }
        return issues;
    }

    // provider and repo together identify an install; a missing provider means legacy GitHub
    public static String manifestKey(String provider, String repo) {
        return (provider == null ? "github" : provider) + "\u0000" + repo;
    }

    public void saveManifest(AppManifest manifest) throws IOException {
        if (manifest == null || !manifest.isValid()) {
            throw new IllegalArgumentException("Invalid application manifest");
        }
        StateJson.write(paths.manifestFile(manifest.provider(), manifest.repo()), manifest);
    }

    public boolean removeManifest(RepoRef ref) throws IOException {
        Optional<AppManifest> found = findManifest(ref);
        if (found.isEmpty()) {
            return false;
        }
        AppManifest manifest = found.get();
        return Files.deleteIfExists(
            paths.manifestFile(manifest.provider(), manifest.repo()));
    }

    // remove the app directory, including backups
    public void deleteApplicationState(RepoRef ref) throws IOException {
        Path dir = paths.appDir(ref.provider(), ref.path());
        if (Files.exists(dir)) {
            FileUtils.deleteRecursively(dir);
        }
    }

    // rewrite the index from the current manifests
    public GrmIndex rebuildIndex() throws IOException {
        List<AppManifest> manifests = scanManifests();
        List<GrmIndex.Entry> entries = new ArrayList<>();
        for (AppManifest manifest : manifests) {
            entries.add(new GrmIndex.Entry(
                manifest.repo(),
                manifest.install().path(),
                manifest,
                manifest.provider()));
        }
        GrmIndex index = new GrmIndex(
            GrmIndex.KIND,
            GrmIndex.SCHEMA_VERSION,
            Instant.now(),
            List.copyOf(entries));
        StateJson.write(paths.indexFile(), index);
        return index;
    }

    // a corrupt index is quarantined and treated as absent
    public Optional<GrmIndex> loadIndex() throws IOException {
        Path file = paths.indexFile();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            GrmIndex index = StateJson.MAPPER.readValue(file.toFile(), GrmIndex.class);
            if (index != null && index.isValid()) {
                return Optional.of(index);
            }
        } catch (JsonProcessingException e) {
            // quarantine below
        }
        quarantine(file, "index");
        return Optional.empty();
    }

    private boolean isManifestFile(Path file) {
        if (!file.getFileName().toString().equals("grm_manifest.json")
            || !Files.isRegularFile(file)) {
            return false;
        }
        for (Path part : file) {
            if (part.toString().equals("backups")) {
                return false;
            }
        }
        return true;
    }

    private Optional<AppManifest> readManifest(Path file) throws IOException {
        AppManifest manifest;
        try {
            manifest = StateJson.MAPPER.readValue(file.toFile(), AppManifest.class);
        } catch (JsonProcessingException e) {
            quarantine(file, "manifest");
            return Optional.empty();
        }
        if (manifest == null) {
            quarantine(file, "manifest");
            return Optional.empty();
        }
        if (manifest.kind() != null && !AppManifest.KIND.equals(manifest.kind())) {
            Output.error("Ignoring foreign manifest: " + file);
            return Optional.empty();
        }
        if (!manifest.isValid()) {
            quarantine(file, "manifest");
            return Optional.empty();
        }
        return Optional.of(manifest);
    }

    private void quarantine(Path file, String label) throws IOException {
        Path quarantined = file.resolveSibling(
            file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
        Files.move(file, quarantined, StandardCopyOption.REPLACE_EXISTING);
        Output.error("Warning: corrupt " + label + " moved to " + quarantined);
    }
}
