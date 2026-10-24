package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.StateStore;

/**
 * Abstract class for platform-specific install and uninstall operations.
 */
public abstract class PlatformHandler {
    protected static final String APP_DATA_DIR = "github-release-manager";

    /**
     * Whether a tracked application is still present on the machine.
     */
    public enum InstallState {
        INSTALLED, MISSING, UNKNOWN
    }

    private final StateStore stateStore;
    private Path installRoot;

    protected PlatformHandler() {
        this(new StateStore(AppPaths.forCurrentPlatform()));
    }

    protected PlatformHandler(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * Overrides the directory applications are installed into.
     * @param root new install root, or null to restore the platform default
     */
    public void setInstallRoot(Path root) {
        this.installRoot = root == null ? null : root.toAbsolutePath().normalize();
    }

    /**
     * @param platformDefault platform-specific fallback root
     * @return the configured root when set, otherwise the platform default
     */
    protected Path configuredRoot(Path platformDefault) {
        return installRoot != null ? installRoot : platformDefault;
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
     * Uninstalls an asset using platform metadata when available.
     * @param asset path to the asset or cached installer
     * @param productCode platform product code, or null
     * @return true if the asset is gone, false otherwise
     */
    public boolean uninstall(Path asset, String productCode) {
        return uninstall(asset);
    }

    /**
     * @param installed path to an installed asset or cached installer
     * @return a platform product code when the platform exposes one, otherwise null
     */
    public String productCode(Path installed) {
        return null;
    }

    /**
     * Reports whether the application tracked by a manifest is still present.
     * @param manifest installed application manifest
     * @return install state
     */
    public InstallState installState(AppManifest manifest) {
        if (manifest == null || manifest.install() == null
            || manifest.install().path() == null) {
            return InstallState.UNKNOWN;
        }
        try {
            return Files.exists(Path.of(manifest.install().path()))
                ? InstallState.INSTALLED
                : InstallState.MISSING;
        } catch (InvalidPathException e) {
            return InstallState.UNKNOWN;
        }
    }

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
