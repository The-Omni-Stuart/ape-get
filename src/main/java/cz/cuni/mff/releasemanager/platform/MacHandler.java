package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import cz.cuni.mff.releasemanager.utils.ChildEnvironment;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;

// macOS installs: mount the DMG, copy the bundle, detach
public final class MacHandler extends PlatformHandler {

    private static MacHandler instance;
    // using a mount point under the home directory to avoid permission issues
    private static final Path MOUNT_DIR = Paths.get(System.getProperty("user.home"), "Volumes");

    private MacHandler() {}

    // singleton
    public static MacHandler getInstance() {
        if (instance == null) {
            instance = new MacHandler();
        }
        return instance;
    }

    @Override
    public Path install(InstallRequest request) {
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
        return new String[] { ".dmg" };
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
