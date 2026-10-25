package cz.cuni.mff.releasemanager.state;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;

// reads manifests and keeps the index in sync when one is saved
public final class ManifestStore {
    private final StateStore stateStore;

    public ManifestStore(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    public Optional<AppManifest> find(RepoRef ref) throws IOException {
        return stateStore.findManifest(ref);
    }

    public List<AppManifest> scan() throws IOException {
        return stateStore.scanManifests();
    }

    public boolean save(AppManifest manifest) {
        try {
            stateStore.saveManifest(manifest);
        } catch (IOException e) {
            Output.error("Installed, but failed to write manifest: " + e.getMessage());
            return false;
        }
        try {
            stateStore.rebuildIndex();
        } catch (IOException e) {
            Output.error("Manifest saved, but failed to rebuild index: " + e.getMessage());
        }
        return true;
    }
}
