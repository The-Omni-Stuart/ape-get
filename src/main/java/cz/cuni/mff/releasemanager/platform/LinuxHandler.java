package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.CommandNames;
import cz.cuni.mff.releasemanager.utils.Executables;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.ProcessRunner;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

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
        if (!supportsKind(request, Platform.OS.LINUX, "Linux")) {
            return null;
        }
        if (request.kind().isPortable()) {
            return installPortable(request);
        }
        try {
            Path assetPath = request.asset();
            if (assetPath == null || !Files.isRegularFile(assetPath)) {
                Output.error("Installation failed: asset is missing");
                return null;
            }
            String repoFullName = request.repoFullName();
            Path launcher = launcherPath(request.provider(), repoFullName);
            if (launcher == null) {
                Output.error("Installation failed: invalid repository name");
                return null;
            }
            if (Files.isDirectory(launcher, LinkOption.NOFOLLOW_LINKS)) {
                Output.error("Refusing to replace directory " + launcher);
                return null;
            }
            String provider = request.provider() == null ? "github" : request.provider();
            Path appDir = statePaths().appDir(provider, repoFullName);
            if (Files.exists(launcher, LinkOption.NOFOLLOW_LINKS)) {
                if (isOwnedByAnotherRepo(
                    launcher, request.provider(), repoFullName)) {
                    Output.error(
                        "Refusing to overwrite " + launcher + " owned by another release.");
                    return null;
                }
                if (!isManagedLauncher(launcher, appDir)
                    && !hasManifestFor(request.provider(), repoFullName)) {
                    Output.error(
                        "Refusing to overwrite unmanaged launcher " + launcher + ".");
                    return null;
                }
            }

            Path versionDir = appDir.resolve("versions")
                .resolve(FileUtils.sanitizeVersion(request.version()));
            Files.createDirectories(versionDir);

            Path target = versionDir.resolve(assetPath.getFileName());
            FileUtils.replaceFile(assetPath, target);
            Files.setPosixFilePermissions(target, EXECUTABLE);

            FileUtils.replaceSymlink(appDir.resolve("current"),
                Paths.get("versions").resolve(versionDir.getFileName().toString()));
            FileUtils.replaceSymlink(launcher,
                appDir.resolve("current").resolve(target.getFileName()));

            Output.info("Installed to: " + target);
            if (!PathEnvironment.isOnPath(launcher.getParent())) {
                Output.error("Note: " + launcher.getParent()
                    + " is not on PATH; run " + launcher + " directly.");
            }
            FileUtils.removeTempDir(assetPath);
            return target;
        } catch (IOException e) {
            Output.error("Installation failed: " + e.getMessage());
            return null;
        }
    }

    // AppImage, APE, and portable exe all use the same versioned single-file layout
    private Path installPortable(InstallRequest request) {
        try {
            if (request.kind() == PackageKind.PORTABLE_EXE) {
                Output.error("Portable Windows executables are not supported on Linux.");
                return null;
            }
            Path assetPath = request.asset();
            if (assetPath == null || !Files.isRegularFile(assetPath)) {
                Output.error("Installation failed: asset is missing");
                return null;
            }
            if (request.assimilate()) {
                if (request.kind() != PackageKind.APE) {
                    Output.error("--assimilate is only supported for APE packages.");
                    return null;
                }
                Path nativeAsset = assimilate(assetPath);
                if (nativeAsset == null) {
                    return null;
                }
                assetPath = nativeAsset;
            }

            String repoFullName = request.repoFullName();
            String command = request.command();
            Path launcher = command == null
                ? null
                : launcherPath(request.provider(), repoFullName, command);
            if (command != null && launcher == null) {
                Output.error("Installation failed: invalid command name.");
                return null;
            }
            if (launcher != null && Files.isDirectory(launcher, LinkOption.NOFOLLOW_LINKS)) {
                Output.error("Refusing to replace directory " + launcher);
                return null;
            }
            if (launcher != null && Files.exists(launcher, LinkOption.NOFOLLOW_LINKS)) {
                if (isOwnedByAnotherRepo(
                    launcher, request.provider(), repoFullName)) {
                    Output.error(
                        "Refusing to overwrite " + launcher + " owned by another release.");
                    return null;
                }
                if (!hasManifestFor(request.provider(), repoFullName)) {
                    Output.error(
                        "Refusing to overwrite unmanaged command " + launcher + ".");
                    return null;
                }
            }

            String provider = request.provider() == null ? "github" : request.provider();
            Path appDir = statePaths().appDir(provider, repoFullName);
            Path versionDir = appDir.resolve("versions")
                .resolve(FileUtils.sanitizeVersion(request.version()));
            Files.createDirectories(versionDir);

            Path target = versionDir.resolve(assetPath.getFileName());
            FileUtils.replaceFile(assetPath, target);
            Files.setPosixFilePermissions(target, EXECUTABLE);

            FileUtils.replaceSymlink(appDir.resolve("current"),
                Paths.get("versions").resolve(versionDir.getFileName().toString()));
            if (launcher != null) {
                FileUtils.replaceSymlink(launcher,
                    appDir.resolve("current").resolve(target.getFileName()));
                Path entry = desktopEntryPath(request.provider(), repoFullName, command);
                if (request.noDesktop()) {
                    if (entry != null) {
                        Files.deleteIfExists(entry);
                    }
                } else if (request.desktop()) {
                    writeDesktopEntry(
                        entry, command, launcher, repoFullName, request.icon(),
                        request.terminal(), request.kind(), target, appDir);
                }
            }

            Output.info("Installed to: " + target);
            if (launcher != null && !PathEnvironment.isOnPath(launcher.getParent())) {
                Output.error("Note: " + launcher.getParent()
                    + " is not on PATH; run " + launcher + " directly.");
            }
            FileUtils.removeTempDir(assetPath);
            return target;
        } catch (IOException | IllegalArgumentException e) {
            Output.error("Installation failed: " + e.getMessage());
            return null;
        }
    }

    private boolean writeDesktopEntry(
        Path entry,
        String command,
        Path launcher,
        String repoFullName,
        String icon,
        boolean terminal,
        PackageKind kind,
        Path asset,
        Path appDir) throws IOException {
        if (entry == null) {
            Output.error("Warning: could not create a desktop entry for " + command + ".");
            return false;
        }
        AppImageDesktop.Metadata metadata = kind == PackageKind.APPIMAGE
            ? AppImageDesktop.read(asset).orElse(null) : null;
        Map<String, String> values = new LinkedHashMap<>();
        if (metadata != null) {
            values.putAll(metadata.entries());
        }
        values.keySet().removeAll(Set.of(
            "Type", "Name", "Exec", "TryExec", "Icon", "Terminal", "Categories",
            "Path", "DBusActivatable"));
        values.values().removeIf(value -> value == null);
        String name = metadata != null && metadata.entries().get("Name") != null
            ? metadata.entries().get("Name")
            : repoFullName == null ? command
                : repoFullName.substring(repoFullName.lastIndexOf('/') + 1);
        String args = execArguments(metadata == null ? null : metadata.entries().get("Exec"));
        String terminalValue = terminal
            ? "true"
            : metadata != null && metadata.entries().get("Terminal") != null
                ? metadata.entries().get("Terminal") : "false";
        String iconValue = installIcon(icon, metadata, appDir);
        String categories = metadata != null && metadata.entries().get("Categories") != null
            ? metadata.entries().get("Categories") : "Utility;";

        StringBuilder content = new StringBuilder()
            .append("[Desktop Entry]\n")
            .append("Type=Application\n")
            .append("Name=").append(name).append('\n')
            .append("Exec=\"").append(launcher).append('"')
            .append(args.isEmpty() ? "" : " " + args).append('\n')
            .append("Terminal=").append(terminalValue).append('\n')
            .append("Categories=").append(categories).append('\n');
        if (iconValue != null) {
            content.append("Icon=").append(iconValue).append('\n');
        }
        for (Map.Entry<String, String> value : values.entrySet()) {
            content.append(value.getKey()).append('=').append(value.getValue()).append('\n');
        }
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, content.toString());
        return true;
    }

    private String installIcon(
        String requestedIcon, AppImageDesktop.Metadata metadata, Path appDir)
        throws IOException {
        byte[] bytes = null;
        String extension = ".png";
        if (requestedIcon != null && !requestedIcon.isBlank()) {
            Path source = Path.of(requestedIcon).toAbsolutePath().normalize();
            if (Files.isRegularFile(source)) {
                bytes = Files.readAllBytes(source);
                String name = source.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot >= 0) {
                    extension = name.substring(dot).toLowerCase(Locale.ROOT);
                }
            } else {
                Output.error("Warning: icon not found: " + source);
            }
        } else if (metadata != null && metadata.icon() != null) {
            bytes = metadata.icon();
            extension = metadata.iconExtension() == null ? ".png" : metadata.iconExtension();
        } else if (metadata != null && metadata.entries().get("Icon") != null) {
            return metadata.entries().get("Icon");
        }
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Path icon = appDir.resolve("desktop").resolve("icon" + extension);
        Files.createDirectories(icon.getParent());
        Files.write(icon, bytes);
        return icon.toString();
    }

    private static String execArguments(String exec) {
        if (exec == null || exec.isBlank()) {
            return "";
        }
        int space = exec.indexOf(' ');
        return space < 0 ? "" : exec.substring(space + 1).trim();
    }

    // explicit opt-in conversion; the APE is still the verified asset
    private Path assimilate(Path ape) throws IOException {
        if (!PackageKind.hasApeMagic(ape)) {
            Output.error("Asset is not an APE; refusing to assimilate.");
            return null;
        }
        if (!Executables.isAvailable("assimilate")) {
            Output.error("--assimilate requires the cosmocc assimilate tool on PATH.");
            return null;
        }
        Path output = ape.resolveSibling(ape.getFileName() + ".native");
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(new ProcessBuilder(
                "assimilate", "-e", "-o", output.toString(), ape.toString()),
                Duration.ofMinutes(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            StagedAsset.discard(output);
            return null;
        }
        if (result.timedOut() || result.exitCode() != 0) {
            String reason = result.timedOut() ? "timed out" : result.output().strip();
            Output.error("Failed to assimilate " + ape.getFileName()
                + (reason.isBlank() ? "." : ": " + reason));
            StagedAsset.discard(output);
            return null;
        }
        StagedAsset.discard(ape);
        return output;
    }

    // the launcher already points at this app's current version
    private static boolean isManagedLauncher(Path launcher, Path appDir) {
        if (!Files.isSymbolicLink(launcher) || launcher.getParent() == null) {
            return false;
        }
        try {
            Path target = launcher.getParent().resolve(
                Files.readSymbolicLink(launcher)).normalize();
            return target.startsWith(appDir.resolve("current").normalize());
        } catch (IOException e) {
            return false;
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
    public Path launcherPath(String provider, String repoFullName, String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        try {
            return getInstallDir().resolve(CommandNames.validate(command));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void activateRestored(AppManifest manifest, Path restoredPath) {
        if (manifest == null || manifest.repo() == null || restoredPath == null
            || manifest.install() == null
            || !PackageKind.fromManifest(manifest.install().kind()).isPortable()) {
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
            FileUtils.replaceSymlink(appDir.resolve("current"),
                Paths.get("versions").resolve(versionDir.getFileName().toString()));
            AppManifest.Uninstall uninstall = manifest.install() == null
                ? null : manifest.install().uninstall();
            if (uninstall != null && uninstall.path() != null
                && !uninstall.path().equals(manifest.install().path())) {
                FileUtils.replaceSymlink(Path.of(uninstall.path()),
                    appDir.resolve("current").resolve(restored.getFileName()));
            }
        } catch (IOException e) {
            Output.error("Warning: could not update install links: " + e.getMessage());
        }
    }

    @Override
    public boolean uninstallPortable(AppManifest manifest) {
        if (manifest == null || manifest.install() == null) {
            return false;
        }
        try {
            AppManifest.Uninstall uninstall = manifest.install().uninstall();
            Path launcher = uninstall == null || uninstall.path() == null
                ? null : Path.of(uninstall.path());
            if (launcher != null
                && (Files.isSymbolicLink(launcher) || Files.isRegularFile(launcher))) {
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

    @Override
    public boolean uninstall(Path asset) {
        Path installDir = getInstallDir();
        if (!isUnder(asset, installDir)) {
            Output.error("Refusing to uninstall path outside " + installDir + ": " + asset);
            return false;
        }
        if (!Files.exists(asset, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try {
            Files.delete(asset);
            return !Files.exists(asset, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            Output.error("Failed to uninstall: " + e.getMessage());
            return false;
        }
    }

    private Path getInstallDir() {
        return configuredRoot(Paths.get(System.getProperty("user.home"), ".local/bin"));
    }

    @Override
    public Path desktopEntryPath(String provider, String repoFullName, String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        try {
            return desktopDir().resolve(CommandNames.validate(command) + ".desktop");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Path desktopDir() {
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = xdg == null || xdg.isBlank()
            ? statePaths().root().getParent()
            : Paths.get(xdg);
        return base.resolve("applications");
    }

    @Override
    public String[] getFormats() {
        return new String[] { ".appimage", ".ape", ".com" };
    }

    @Override
    public Path getInstallRoot() {
        return getInstallDir();
    }

}
