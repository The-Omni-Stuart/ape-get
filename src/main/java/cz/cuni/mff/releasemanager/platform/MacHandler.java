package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.ChildEnvironment;
import cz.cuni.mff.releasemanager.utils.CommandNames;
import cz.cuni.mff.releasemanager.utils.Executables;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

// macOS installs DMGs into Applications and portable binaries into ~/.local/bin
public final class MacHandler extends PlatformHandler {

    private static final Set<PosixFilePermission> EXECUTABLE = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE);

    private static MacHandler instance;
    // using a mount point under the home directory to avoid permission issues
    private static final Path MOUNT_DIR = Paths.get(System.getProperty("user.home"), "Volumes");

    private MacHandler() {}

    MacHandler(StateStore stateStore) {
        super(stateStore);
    }

    // singleton
    public static MacHandler getInstance() {
        if (instance == null) {
            instance = new MacHandler();
        }
        return instance;
    }

    @Override
    public Path install(InstallRequest request) {
        if (request.kind().isPortable()) {
            return installPortable(request);
        }
        boolean mounted = false;
        Path stagingDir = null;
        try {
            Path asset = request.asset();
            mount(asset);
            mounted = true;

            Path targetDir = getInstallRoot();
            Files.createDirectories(targetDir);

            Path app = findAppInMountDir(asset.getFileName().toString());
            Path target = targetDir.resolve(app.getFileName());
            if (Files.exists(target)
                && isOwnedByAnotherRepo(target, request.provider(), request.repoFullName())) {
                Output.error("Refusing to overwrite " + target + " owned by another release.");
                return null;
            }

            stagingDir = Files.createTempDirectory(targetDir, ".ape-get-staging-");
            Path stagedApp = stagingDir.resolve(app.getFileName());
            Process copyProcess = process()
                .command("ditto", app.toString(), stagedApp.toString())
                .inheritIO()
                .start();
            if (copyProcess.waitFor() != 0) {
                throw new IOException("Failed to copy application.");
            }

            FileUtils.replaceDirectory(stagedApp, target);
            return target;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error("Installation error: " + e.getMessage());
            return null;
        } finally {
            if (mounted) {
                try {
                    detach();
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    Output.error("Failed to detach " + MOUNT_DIR + ": " + e.getMessage());
                }
            }
            if (stagingDir != null) {
                try {
                    FileUtils.deleteRecursively(stagingDir);
                } catch (IOException e) {
                    Output.error("Failed to remove staging directory: " + e.getMessage());
                }
            }
        }
    }

    // process builder with provider tokens stripped
    private static ProcessBuilder process(String... command) {
        return ChildEnvironment.sanitize(new ProcessBuilder(command));
    }

    // mount the DMG on a private mount point
    private void mount(Path asset) throws IOException, InterruptedException {
        Files.createDirectories(MOUNT_DIR);
        Files.setPosixFilePermissions(MOUNT_DIR, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
        ));
        Process process = process()
                .command("hdiutil", "attach", "-nobrowse", "-mountpoint",
                    MOUNT_DIR.toAbsolutePath().toString(),
                    asset.toString())
                .inheritIO()
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Installation failed with exit code: " + exitCode);
        }
    }

    // DMG names usually include the version, so exact name is only a hint
    private Path findAppInMountDir(String dmgName) throws IOException {
        try (var paths = Files.walk(MOUNT_DIR, 2)) {
            List<Path> apps = paths
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".app"))
                .toList();
            return chooseApp(apps, dmgName);
        }
    }

    static Path chooseApp(List<Path> entries, String dmgName) throws IOException {
        String expected = dmgName.toLowerCase(Locale.ROOT).endsWith(".dmg")
            ? dmgName.substring(0, dmgName.length() - 4) + ".app"
            : dmgName + ".app";
        List<Path> apps = entries.stream()
            .filter(path -> path.getFileName().toString()
                .toLowerCase(Locale.ROOT).endsWith(".app"))
            .toList();
        for (Path app : apps) {
            if (app.getFileName().toString().equalsIgnoreCase(expected)) {
                return app;
            }
        }
        return apps.stream().findFirst()
            .orElseThrow(() -> new IOException("No .app bundle found"));
    }

    // force-detach the image
    private void detach() throws IOException, InterruptedException {
        Process process = process()
                .command("hdiutil", "detach", MOUNT_DIR.toString(), "-force")
                .inheritIO()
                .start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Detaching failed with exit code: " + exitCode);
        }
    }

    // APE and portable binaries live in the user's bin dir, not /Applications
    private Path installPortable(InstallRequest request) {
        try {
            if (request.kind() == PackageKind.APPIMAGE
                || request.kind() == PackageKind.PORTABLE_EXE) {
                Output.error("This package kind is not supported on macOS.");
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

            String command = request.command();
            Path launcher = command == null
                ? null
                : launcherPath(request.provider(), request.repoFullName(), command);
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
                    launcher, request.provider(), request.repoFullName())) {
                    Output.error(
                        "Refusing to overwrite " + launcher + " owned by another release.");
                    return null;
                }
                if (!hasManifestFor(request.provider(), request.repoFullName())) {
                    Output.error(
                        "Refusing to overwrite unmanaged command " + launcher + ".");
                    return null;
                }
            }

            String provider = request.provider() == null ? "github" : request.provider();
            Path appDir = statePaths().appDir(provider, request.repoFullName());
            Path versionDir = appDir.resolve("versions")
                .resolve(sanitizeVersion(request.version()));
            Files.createDirectories(versionDir);
            Path target = versionDir.resolve(assetPath.getFileName());
            FileUtils.replaceFile(assetPath, target);
            Files.setPosixFilePermissions(target, EXECUTABLE);

            replaceSymlink(appDir.resolve("current"),
                Paths.get("versions").resolve(versionDir.getFileName().toString()));
            if (launcher != null) {
                replaceSymlink(launcher,
                    appDir.resolve("current").resolve(target.getFileName()));
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
        Process process = new ProcessBuilder(
            "assimilate", "-o", output.toString(), ape.toString())
            .inheritIO()
            .start();
        try {
            if (process.waitFor() != 0) {
                Output.error("Failed to assimilate " + ape.getFileName() + ".");
                StagedAsset.discard(output);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            StagedAsset.discard(output);
            return null;
        }
        StagedAsset.discard(ape);
        return output;
    }

    private static Path portableBinDir() {
        return Paths.get(System.getProperty("user.home"), ".local", "bin");
    }

    @Override
    public Path launcherPath(String provider, String repoFullName, String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        try {
            return portableBinDir().resolve(CommandNames.validate(command));
        } catch (IllegalArgumentException e) {
            return null;
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
    public void activateRestored(AppManifest manifest, Path restoredPath) {
        if (manifest == null || manifest.install() == null || restoredPath == null
            || !PackageKind.fromManifest(manifest.install().kind()).isPortable()) {
            return;
        }
        AppManifest.Uninstall uninstall = manifest.install().uninstall();
        if (uninstall == null || uninstall.path() == null) {
            return;
        }
        try {
            replaceSymlink(Path.of(uninstall.path()), restoredPath);
        } catch (IOException e) {
            Output.error("Warning: could not update portable launcher: " + e.getMessage());
        }
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

    private static String sanitizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "unknown";
        }
        String value = version.trim().replaceAll("[^A-Za-z0-9._+-]+", "-");
        while (value.startsWith(".") || value.startsWith("-")) {
            value = value.substring(1);
        }
        value = value.replace("..", ".");
        while (value.startsWith(".") || value.startsWith("-")) {
            value = value.substring(1);
        }
        return value.isBlank() ? "unknown" : value;
    }

    @Override
    public boolean uninstall(Path asset) {
        if (!isAllowedUninstallPath(asset)) {
            Output.error("Refusing to uninstall path outside Applications: " + asset);
            return false;
        }
        if (!Files.exists(asset, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try {
            FileUtils.deleteRecursively(asset);
            return true;
        } catch (IOException e) {
            Output.error("Uninstallation error: " + e.getMessage());
            return false;
        }
    }

    private boolean isAllowedUninstallPath(Path asset) {
        return isUnder(asset, getInstallRoot())
            || isUnder(asset, Paths.get(System.getProperty("user.home"), "Applications"));
    }

    @Override
    public String[] getFormats() {
        return new String[] { ".dmg", ".ape", ".com" };
    }

    @Override
    public Path getInstallRoot() {
        return configuredRoot(Paths.get("/Applications"));
    }

    @Override
    public Path mountPoint() {
        return MOUNT_DIR;
    }

}
