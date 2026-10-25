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

/**
 * Reads and writes grm_manifest.json files and rebuilds grm_index.json.
 */
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

    /**
     * Persists tool configuration.
     * @param config configuration to write
     * @throws IOException if the file cannot be written
     */
    public void saveConfig(GrmConfig config) throws IOException {
        configStore.save(config);
    }

    /**
     * Scans application directories for valid manifests. Foreign files with a
     * different kind are ignored; corrupt files are quarantined.
     * @return valid manifests sorted by repo
     * @throws IOException if the applications directory cannot be read
     */
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

    /**
     * Bare repository lookup, defaulting to GitHub.
     * @param repo owner/repo path
     * @return manifest when present
     * @throws IOException if manifests cannot be read
     */
    public Optional<AppManifest> findManifest(String repo) throws IOException {
        return findManifest(new RepoRef(RepoRef.DEFAULT_PROVIDER, repo));
    }

    /**
     * Finds index entries that need an unsafe recovery decision.
     * @param index current index
     * @param manifests currently valid manifests
     * @return ordered recovery issues
     */
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

    private static String manifestKey(String provider, String repo) {
        return (provider == null ? RepoRef.DEFAULT_PROVIDER : provider) + "\u0000" + repo;
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

    /**
     * Deletes the per-application state directory, including backups.
     * @param ref application reference
     * @throws IOException if the directory cannot be removed
     */
    public void deleteApplicationState(RepoRef ref) throws IOException {
        Path dir = paths.appDir(ref.provider(), ref.path());
        if (Files.exists(dir)) {
            FileUtils.deleteRecursively(dir);
        }
    }

    public boolean removeManifest(String repo) throws IOException {
        return removeManifest(new RepoRef(RepoRef.DEFAULT_PROVIDER, repo));
    }

    /**
     * Rebuilds the derived index from all valid manifests.
     * @return the new index
     * @throws IOException if manifests cannot be read or the index cannot be written
     */
    public GrmIndex rebuildIndex() throws IOException {
        List<AppManifest> manifests = scanManifests();
        List<GrmIndex.Entry> entries = new ArrayList<>();
        for (AppManifest manifest : manifests) {
            Path manifestFile = paths.manifestFile(manifest.provider(), manifest.repo());
            String relative = paths.root()
                .relativize(manifestFile)
                .toString()
                .replace('\\', '/');
            entries.add(new GrmIndex.Entry(
                manifest.repo(),
                manifest.version(),
                manifest.installedAt(),
                manifest.install().path(),
                relative,
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

    /**
     * Loads the derived index. A corrupt index is quarantined and treated as absent.
     * @return index when present and valid
     * @throws IOException if the index cannot be read or moved aside
     */
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
            System.err.println("Ignoring foreign manifest: " + file);
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
        System.err.println("Warning: corrupt " + label + " moved to " + quarantined);
    }
}
