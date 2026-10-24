package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.StateStore;

/**
 * Abstract class for platform-specific install and uninstall operations.
 */
public abstract class PlatformHandler {
    protected static final String APP_DATA_DIR = "github-release-manager";

    private final StateStore stateStore;

    protected PlatformHandler() {
        this(new StateStore(AppPaths.forCurrentPlatform()));
    }

    protected PlatformHandler(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * Installs the asset.
     * @param asset Path to the asset to install.
     * @param repoFullName Repository in owner/name form.
     * @return Path to the installed application which is then used for uninstallation.
     */
    public abstract Path install(Path asset, String repoFullName);

    /**
     * Uninstalls the asset.
     * @param asset Path to the asset to uninstall.
     * @return true if the asset is gone, false if it could not be removed.
     */
    public abstract boolean uninstall(Path asset);

    /**
     * @return Supported file formats for installation.
     */
    public abstract String[] getFormats();

    /**
     * @return Root directory where this platform installs applications.
     */
    public abstract Path getInstallRoot();

    /**
     * Checks whether a path is strictly inside a root directory.
     * @param path path to check
     * @param root allowed root directory
     * @return true if path is under root and is not root itself
     */
    protected static boolean isUnder(Path path, Path root) {
        if (path == null || root == null) {
            return false;
        }
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedRoot) && !normalizedPath.equals(normalizedRoot);
    }

    /**
     * Checks whether a target path is already owned by a different installed repository.
     * @param target path or directory that would be overwritten
     * @param repoFullName repository requesting the target
     * @return true if another repository owns the target or the state cannot be read
     */
    protected boolean isOwnedByAnotherRepo(Path target, String repoFullName) {
        try {
            Path normalizedTarget = target.toAbsolutePath().normalize();
            return stateStore.scanManifests().stream()
                .filter(manifest -> !manifest.repo().equals(repoFullName))
                .map(manifest -> Path.of(manifest.install().path())
                    .toAbsolutePath()
                    .normalize())
                .anyMatch(path -> path.equals(normalizedTarget) || path.startsWith(normalizedTarget));
        } catch (IOException | InvalidPathException e) {
            return true;
        }
    }
}
