package cz.cuni.mff.releasemanager.install;

import java.io.IOException;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;

// removes the live install and its manifest
public final class UninstallService {
    private final StateStore stateStore;
    private final PlatformHandler platformHandler;

    public UninstallService(StateStore stateStore, PlatformHandler platformHandler) {
        this.stateStore = stateStore;
        this.platformHandler = platformHandler;
    }

    public boolean remove(AppManifest manifest) {
        AppManifest.Uninstall uninstall = manifest.install().uninstall();
        String uninstallPath = uninstall != null && uninstall.path() != null
            ? uninstall.path()
            : manifest.install().path();
        String productCode = uninstall == null ? null : uninstall.productCode();
        boolean removed = productCode == null || productCode.isBlank()
            ? platformHandler.uninstall(Path.of(uninstallPath))
            : platformHandler.uninstall(Path.of(uninstallPath), productCode);
        if (!removed) {
            Output.error("Failed to uninstall " + manifest.repo() + ".");
            return false;
        }

        try {
            if (!stateStore.removeManifest(
                new RepoRef(manifest.provider(), manifest.repo()))) {
                Output.error(
                    "Uninstalled " + manifest.repo() + ", but failed to remove its manifest.");
                return false;
            }
            stateStore.deleteApplicationState(
                new RepoRef(manifest.provider(), manifest.repo()));
        } catch (IOException e) {
            Output.error(
                "Uninstalled " + manifest.repo() + ", but failed to update state: "
                    + e.getMessage());
            return false;
        }
        return true;
    }
}
