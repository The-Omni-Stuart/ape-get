package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.utils.CommandNames;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;

// portable exe and APE installs, shims, and uninstalls
final class WindowsPortableInstaller {
    private final PlatformHandler handler;

    WindowsPortableInstaller(PlatformHandler handler) {
        this.handler = handler;
    }

    Path install(InstallRequest request) {
        try {
            if (request.kind() == PackageKind.APPIMAGE) {
                Output.error("AppImages are not supported on Windows.");
                return null;
            }
            Path assetPath = request.asset();
            if (assetPath == null || !Files.isRegularFile(assetPath)) {
                Output.error("Installation failed: asset is missing");
                return null;
            }
            if (request.assimilate()) {
                Output.error("--assimilate is not supported on Windows.");
                return null;
            }
            String command = request.command();
            Path launcher = command == null
                ? null
                : launcherPath(request.provider(), request.repoFullName(), command);
            if (command != null && launcher == null) {
                Output.error("Installation failed: invalid command name.");
                return null;
            }
            if (launcher != null && Files.exists(launcher)) {
                if (handler.isOwnedByAnotherRepo(
                    launcher, request.provider(), request.repoFullName())) {
                    Output.error(
                        "Refusing to overwrite " + launcher + " owned by another release.");
                    return null;
                }
                if (!handler.hasManifestFor(request.provider(), request.repoFullName())) {
                    Output.error(
                        "Refusing to overwrite unmanaged command " + launcher + ".");
                    return null;
                }
            }

            String provider = request.provider() == null ? "github" : request.provider();
            Path appDir = handler.statePaths().appDir(provider, request.repoFullName());
            Path versionDir = appDir.resolve("versions")
                .resolve(FileUtils.sanitizeVersion(request.version()));
            Files.createDirectories(versionDir);
            Path target = versionDir.resolve(assetPath.getFileName());
            FileUtils.replaceFile(assetPath, target);
            FileUtils.removeTempDir(assetPath);

            if (launcher != null) {
                writeShim(launcher, target);
            }
            Output.info("Installed to: " + target);
            if (launcher != null && !PathEnvironment.isOnPath(launcher.getParent())) {
                Output.error("Note: " + launcher.getParent()
                    + " is not on PATH; run " + launcher + " directly.");
            }
            return target;
        } catch (IOException | IllegalArgumentException e) {
            Output.error("Installation failed: " + e.getMessage());
            return null;
        }
    }

    Path launcherPath(String provider, String repoFullName, String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        try {
            return WindowsSupport.portableBinDir()
                .resolve(CommandNames.validate(command) + ".cmd");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    boolean uninstall(AppManifest manifest) {
        if (manifest == null || manifest.install() == null) {
            return false;
        }
        try {
            AppManifest.Uninstall uninstall = manifest.install().uninstall();
            Path launcher = uninstall == null || uninstall.path() == null
                ? null : Path.of(uninstall.path());
            if (launcher != null && Files.isRegularFile(launcher)) {
                Files.deleteIfExists(launcher);
            }
            if (manifest.install().desktopEntry() != null) {
                Files.deleteIfExists(Path.of(manifest.install().desktopEntry()));
            }
            return true;
        } catch (IOException e) {
            Output.error("Failed to uninstall portable package: " + e.getMessage());
            return false;
        }
    }

    void activateRestored(AppManifest manifest, Path restoredPath) {
        if (manifest == null || manifest.install() == null || restoredPath == null
            || !PackageKind.fromManifest(manifest.install().kind()).isPortable()) {
            return;
        }
        AppManifest.Uninstall uninstall = manifest.install().uninstall();
        if (uninstall == null || uninstall.path() == null
            || uninstall.path().equals(manifest.install().path())) {
            return;
        }
        try {
            writeShim(Path.of(uninstall.path()), restoredPath);
        } catch (IOException e) {
            Output.error("Warning: could not update portable launcher: " + e.getMessage());
        }
    }

    private static void writeShim(Path launcher, Path target) throws IOException {
        Files.createDirectories(launcher.getParent());
        String content = "@echo off\r\n\"" + target + "\" %*\r\n";
        Files.writeString(launcher, content);
    }
}
