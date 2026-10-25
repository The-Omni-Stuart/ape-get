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
     * Installs the asset using the full request, including the provider and version.
     * @param request install operation
     * @return Path to the live installed file
     */
    public Path install(InstallRequest request) {
        return install(request.asset(), request.repoFullName());
    }

    /**
     * @param repoFullName repository in owner/name form
     * @return stable launcher path when the platform uses one, otherwise null
     */
    public Path launcherPath(String repoFullName) {
        return null;
    }

    /**
     * Re-points platform links after a backup has been restored.
     * @param manifest manifest of the restored release
     * @param restoredPath path the backup artifact was restored to
     */
    public void activateRestored(AppManifest manifest, Path restoredPath) {
        // platforms without versioned installs need no extra step
    }

    /**
     * @param manifest installed application manifest
     * @return true when this platform can roll back by reinstalling a cached installer
     */
    public boolean supportsInstallerRollback(AppManifest manifest) {
        return false;
    }

    /**
     * Rolls an installer-based application back by uninstalling the current
     * product and reinstalling the cached installer.
     * @param current manifest of the installed release
     * @param restored manifest to restore
     * @param installer cached installer for the restored release
     * @return true when the rollback completed
     */
    public boolean rollbackInstaller(
        AppManifest current, AppManifest restored, Path installer) {
        return false;
    }

    /**
     * @param manifest installed application manifest
     * @return true when this platform can create a usable backup for it
     */
    public boolean supportsBackup(AppManifest manifest) {
        return true;
    }

    /**
     * @return paths used for per-app state
     */
    protected AppPaths statePaths() {
        return stateStore.paths();
    }

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
     * @return root directory where this platform installs applications.
     */
    public abstract Path getInstallRoot();

    /**
     * @return a temporary mount point this platform manages, or null
     */
    public Path mountPoint() {
        return null;
    }

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
            for (AppManifest manifest : stateStore.scanManifests()) {
                if (manifest.repo() == null || manifest.repo().equals(repoFullName)
                    || manifest.install() == null) {
                    continue;
                }
                if (owns(manifest.install().path(), normalizedTarget)) {
                    return true;
                }
                AppManifest.Uninstall uninstall = manifest.install().uninstall();
                if (uninstall != null && owns(uninstall.path(), normalizedTarget)) {
                    return true;
                }
            }
            return false;
        } catch (IOException | InvalidPathException e) {
            return true;
        }
    }

    private static boolean owns(String path, Path normalizedTarget) {
        if (path == null || path.isBlank()) {
            return false;
        }
        Path owned = Path.of(path).toAbsolutePath().normalize();
        return owned.equals(normalizedTarget) || owned.startsWith(normalizedTarget);
    }
}
