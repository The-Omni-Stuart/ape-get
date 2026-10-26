package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;

// shared install and uninstall contract for each platform
public abstract class PlatformHandler {
    protected static final String APP_DATA_DIR = "github-release-manager";

    // what the state directory thinks of a live install
    public enum InstallState {
        INSTALLED, MISSING, UNKNOWN
    }

    private final StateStore stateStore;
    private Path installRoot;

    protected PlatformHandler() {
        this(new StateStore(PlatformPaths.current()));
    }

    protected PlatformHandler(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    // config install_root override; null restores the platform default
    public void setInstallRoot(Path root) {
        this.installRoot = root == null ? null : root.toAbsolutePath().normalize();
    }

    // config override, else the platform default
    protected Path configuredRoot(Path platformDefault) {
        return installRoot != null ? installRoot : platformDefault;
    }

    // provider and repo identify the install; version is used for versioned layouts
    public abstract Path install(InstallRequest request);

    // --kind and --asset can bypass platform filtering, so handlers re-check
    protected static boolean supportsKind(
        InstallRequest request, Platform.OS os, String platformName) {
        if (request.kind().supportsOn(os)) {
            return true;
        }
        Output.error("Package kind " + request.kind().manifestName()
            + " is not supported on " + platformName + ".");
        return false;
    }

    // platforms without a stable launcher return null
    public Path launcherPath(String provider, String repoFullName) {
        return null;
    }

    // portable packages use a user-facing command instead of a package id
    public Path launcherPath(String provider, String repoFullName, String command) {
        return launcherPath(provider, repoFullName);
    }

    // portable packages own their versioned files and launcher
    public boolean uninstallPortable(AppManifest manifest) {
        return false;
    }

    // Linux uses a .desktop entry; other platforms have no equivalent yet
    public Path desktopEntryPath(String provider, String repoFullName, String command) {
        return null;
    }

    // repoint links after a backup has been restored
    public void activateRestored(AppManifest manifest, Path restoredPath) {
        // platforms without versioned installs need no extra step
    }

    // true when a cached installer can reinstall the old version
    public boolean supportsInstallerRollback(AppManifest manifest) {
        return false;
    }

    // uninstall the current version, then reinstall the cached copy
    public boolean rollbackInstaller(
        AppManifest current, AppManifest restored, Path installer) {
        return false;
    }

    // true when a backup of this install can be used
    public boolean supportsBackup(AppManifest manifest) {
        return true;
    }

    // per-app state paths
    protected AppPaths statePaths() {
        return stateStore.paths();
    }

    // true when the app is gone
    public abstract boolean uninstall(Path asset);

    // uses the product code when the platform has one
    public boolean uninstall(Path asset, String productCode) {
        return uninstall(asset);
    }

    // platform product code, or null
    public String productCode(Path installed) {
        return null;
    }

    // installed, missing, or unknown from the live path
    public InstallState installState(AppManifest manifest) {
        return defaultInstallState(manifest);
    }

    // file-existence check shared with platform-specific state readers
    protected InstallState defaultInstallState(AppManifest manifest) {
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

    // extensions this platform can install
    public abstract String[] getFormats();

    // overridable when the caller already knows the machine
    public Platform.Architecture getArchitecture() {
        return Platform.detectPlatform().getArchitecture();
    }

    // where this platform installs apps
    public abstract Path getInstallRoot();

    // platform-managed temporary mount, or null
    public Path mountPoint() {
        return null;
    }

    // true when path is strictly inside root
    protected static boolean isUnder(Path path, Path root) {
        if (path == null || root == null) {
            return false;
        }
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedRoot) && !normalizedPath.equals(normalizedRoot);
    }

    // true when this package already has a manifest
    protected boolean hasManifestFor(String provider, String repoFullName) {
        try {
            return stateStore.findManifest(new RepoRef(provider, repoFullName)).isPresent();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // true when another provider/repo already claims the target
    protected boolean isOwnedByAnotherRepo(
        Path target, String provider, String repoFullName) {
        try {
            Path normalizedTarget = target.toAbsolutePath().normalize();
            for (AppManifest manifest : stateStore.scanManifests()) {
                if (manifest.repo() == null || manifest.install() == null
                    || (java.util.Objects.equals(provider, manifest.provider())
                        && repoFullName.equals(manifest.repo()))) {
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
