package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;

// Linux installs live in versioned dirs behind a stable launcher symlink
public final class LinuxHandler extends PlatformHandler {

    private static final Set<PosixFilePermission> EXECUTABLE = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE);

    private static LinuxHandler instance;

    private LinuxHandler() {}

    LinuxHandler(StateStore stateStore) {
        super(stateStore);
    }

    // singleton
    public static LinuxHandler getInstance() {
        if (instance == null) {
            instance = new LinuxHandler();
        }
        return instance;
    }

    @Override
    public Path install(InstallRequest request) {
        try {
            Path assetPath = request.asset();
            if (assetPath == null || !Files.isRegularFile(assetPath)) {
                System.err.println("Installation failed: asset is missing");
                return null;
            }
            String repoFullName = request.repoFullName();
            Path launcher = launcherPath(request.provider(), repoFullName);
            if (launcher == null) {
                System.err.println("Installation failed: invalid repository name");
                return null;
            }
            if (Files.isDirectory(launcher, LinkOption.NOFOLLOW_LINKS)) {
                System.err.println("Refusing to replace directory " + launcher);
                return null;
            }
            if (Files.exists(launcher, LinkOption.NOFOLLOW_LINKS)
                && isOwnedByAnotherRepo(
                    launcher, request.provider(), repoFullName)) {
                System.err.println(
                    "Refusing to overwrite " + launcher + " owned by another release.");
                return null;
            }

            String provider = request.provider() == null ? "github" : request.provider();
            Path appDir = statePaths().appDir(provider, repoFullName);
            Path versionDir = appDir.resolve("versions")
                .resolve(sanitizeVersion(request.version()));
            Files.createDirectories(versionDir);

            Path target = versionDir.resolve(assetPath.getFileName());
            FileUtils.replaceFile(assetPath, target);
            Files.setPosixFilePermissions(target, EXECUTABLE);

            replaceSymlink(appDir.resolve("current"),
                Paths.get("versions").resolve(versionDir.getFileName().toString()));
            replaceSymlink(launcher,
                appDir.resolve("current").resolve(target.getFileName()));

            Output.info("Installed to: " + target);
            if (!PathEnvironment.isOnPath(launcher.getParent())) {
                System.err.println("Note: " + launcher.getParent()
                    + " is not on PATH; run " + launcher + " directly.");
            }
            FileUtils.removeTempDir(assetPath);
            return target;
        } catch (IOException e) {
            System.err.println("Installation failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public Path launcherPath(String provider, String repoFullName) {
        if (repoFullName == null || repoFullName.isBlank()) {
            return null;
        }
        return getInstallDir().resolve(FileUtils.installName(provider, repoFullName));
    }

    @Override
    public void activateRestored(AppManifest manifest, Path restoredPath) {
        if (manifest == null || manifest.repo() == null || restoredPath == null) {
            return;
        }
        try {
            Path appDir = statePaths().appDir(manifest.provider(), manifest.repo());
            Path versions = appDir.resolve("versions").toAbsolutePath().normalize();
            Path restored = restoredPath.toAbsolutePath().normalize();
            if (!restored.startsWith(versions) || restored.equals(versions)) {
                return;
            }
            Path versionDir = restored.getParent();
            if (versionDir == null || versionDir.equals(versions)) {
                return;
            }
            replaceSymlink(appDir.resolve("current"),
                Paths.get("versions").resolve(versionDir.getFileName().toString()));
            Path launcher = launcherPath(manifest.provider(), manifest.repo());
            if (launcher != null) {
                replaceSymlink(launcher,
                    appDir.resolve("current").resolve(restored.getFileName()));
            }
        } catch (IOException e) {
            System.err.println("Warning: could not update install links: " + e.getMessage());
        }
    }

    @Override
    public boolean uninstall(Path asset) {
        Path installDir = getInstallDir();
        if (!isUnder(asset, installDir)) {
            System.err.println("Refusing to uninstall path outside " + installDir + ": " + asset);
            return false;
        }
        if (!Files.exists(asset, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try {
            Files.delete(asset);
            return !Files.exists(asset, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            System.err.println("Failed to uninstall: " + e.getMessage());
            return false;
        }
    }

    private Path getInstallDir() {
        return configuredRoot(Paths.get(System.getProperty("user.home"), ".local/bin"));
    }

    @Override
    public String[] getFormats() {
        return new String[] { ".appimage" };
    }

    @Override
    public Path getInstallRoot() {
        return getInstallDir();
    }

    // one safe path segment for the version directory
    static String sanitizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "unknown";
        }
        String value = version.trim()
            .replaceAll("[^A-Za-z0-9._+-]+", "-");
        while (value.startsWith(".") || value.startsWith("-")) {
            value = value.substring(1);
        }
        value = value.replace("..", ".");
        while (value.startsWith(".") || value.startsWith("-")) {
            value = value.substring(1);
        }
        return value.isBlank() ? "unknown" : value;
    }

    private static void replaceSymlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.toAbsolutePath().getParent());
        Path temp = link.resolveSibling(
            "." + link.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createSymbolicLink(temp, target);
        try {
            Files.move(temp, link, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, link, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
