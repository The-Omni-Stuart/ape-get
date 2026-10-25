package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.GrmIndex;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.Output;

// loads config and repairs manifests, the index, and recovery state before a command runs
public final class StateBootstrap {

    private final StateStore stateStore;
    private final Predicate<String> confirm;

    public StateBootstrap(StateStore stateStore, Predicate<String> confirm) {
        this.stateStore = stateStore;
        this.confirm = confirm;
    }

    public GrmConfig prepare() {
        ConfigStore.LoadResult loaded;
        try {
            loaded = stateStore.loadConfig();
        } catch (IOException e) {
            System.err.println("Failed to initialise state at "
                + stateStore.paths().root() + ": " + e.getMessage());
            return null;
        }
        GrmConfig config = loaded.config();

        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            System.err.println("Failed to read application manifests: " + e.getMessage());
            return null;
        }

        Optional<GrmIndex> index;
        try {
            index = stateStore.loadIndex();
        } catch (IOException e) {
            System.err.println("Failed to read index: " + e.getMessage());
            return null;
        }

        if (index.isEmpty()) {
            try {
                stateStore.rebuildIndex();
            } catch (IOException e) {
                System.err.println("Failed to create index at "
                    + stateStore.paths().indexFile() + ": " + e.getMessage());
                return null;
            }
            if (loaded.created()) {
                printSetup();
            } else {
                Output.info("Rebuilt index at " + stateStore.paths().indexFile());
            }
            return config;
        }

        boolean changed = false;
        for (StateStore.RecoveryIssue issue : stateStore.findRecoveryIssues(index.get(), manifests)) {
            if (issue.kind() == StateStore.RecoveryKind.RESTORE_MANIFEST) {
                if (!confirm.test(restoreMessage(issue.entry()))) {
                    System.err.println("Manifest recovery declined for " + issue.entry().repo() + ".");
                    return null;
                }
                try {
                    stateStore.saveManifest(issue.entry().snapshot());
                } catch (IOException e) {
                    System.err.println("Failed to restore manifest for "
                        + issue.entry().repo() + ": " + e.getMessage());
                    return null;
                }
                Output.info("Restored manifest for " + issue.entry().repo() + ".");
                changed = true;
            } else {
                if (!confirm.test(dropMessage(issue.entry()))) {
                    System.err.println("Stale index entry kept for " + issue.entry().repo() + ".");
                    return null;
                }
                Output.info("Removed stale index entry for " + issue.entry().repo() + ".");
                changed = true;
            }
        }

        Set<String> indexedRepos = repos(index.get());
        Set<String> manifestRepos = new HashSet<>();
        for (AppManifest manifest : manifests) {
            if (manifest.repo() != null) {
                manifestRepos.add(
                    StateStore.manifestKey(manifest.provider(), manifest.repo()));
            }
        }
        if (changed || !indexedRepos.equals(manifestRepos)) {
            try {
                stateStore.rebuildIndex();
            } catch (IOException e) {
                System.err.println("Failed to rebuild index: " + e.getMessage());
                return null;
            }
        }

        if (loaded.created()) {
            printSetup();
        }
        return config;
    }


    private void printSetup() {
        Output.info("First-time setup complete.");
        Output.info("  config: " + stateStore.paths().configFile());
        Output.info("  index:  " + stateStore.paths().indexFile());
    }


    private static Set<String> repos(GrmIndex index) {
        Set<String> repos = new HashSet<>();
        for (GrmIndex.Entry entry : index.apps()) {
            if (entry != null && entry.repo() != null) {
                repos.add(StateStore.manifestKey(entry.provider(), entry.repo()));
            }
        }
        return repos;
    }


    private String restoreMessage(GrmIndex.Entry entry) {
        String provider = entry.snapshot() != null && entry.snapshot().provider() != null
            ? entry.snapshot().provider()
            : "github";
        return "The manifest for " + entry.repo() + " is missing:\n  "
            + stateStore.paths().manifestFile(provider, entry.repo())
            + "\nThe index has a recovery snapshot and the app is installed at:\n  "
            + entry.installPath()
            + "\nRestore the manifest from the snapshot?";
    }


    private static String dropMessage(GrmIndex.Entry entry) {
        return "The index contains an entry for " + entry.repo()
            + ", but its manifest is missing.\nRemove the stale index entry?";
    }
}
