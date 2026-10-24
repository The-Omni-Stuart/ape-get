package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import cz.cuni.mff.releasemanager.utils.FileUtils;

/**
 * LinuxHandler is responsible for handling the installation and uninstallation of applications on Linux systems.
 */
public final class LinuxHandler extends PlatformHandler {

    private static LinuxHandler instance;

    private LinuxHandler() {}

    /**
     * @return  Singleton instance of LinuxHandler.
     */
    public static LinuxHandler getInstance() {
        if (instance == null) {
            instance = new LinuxHandler();
        }
        return instance;
    }

    @Override
    public Path install(Path assetPath, String repoFullName) {
        try {
            Files.setPosixFilePermissions(assetPath, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            ));

            Path targetDir = getInstallDir();
            Files.createDirectories(targetDir);

            String installName = FileUtils.getInstallName(repoFullName, assetPath);
            Path target = targetDir.resolve(installName);
            if (Files.exists(target) && isOwnedByAnotherRepo(target, repoFullName)) {
                System.err.println("Refusing to overwrite " + target + " owned by another release.");
                return null;
            }

            FileUtils.replaceFile(assetPath, target);
            System.out.println("Installed to: " + target);
            FileUtils.removeTempDir(assetPath);
            return target;

        } catch (IOException e) {
            System.err.println("Installation failed");
        }
        return null;
    }

    @Override
    public boolean uninstall(Path asset) {
        Path installDir = getInstallDir();
        if (!isUnder(asset, installDir)) {
            System.err.println("Refusing to uninstall path outside " + installDir + ": " + asset);
            return false;
        }
        if (!Files.isRegularFile(asset)) {
            return !Files.exists(asset);
        }
        try {
            Files.deleteIfExists(asset);
            return !Files.exists(asset);
        } catch (IOException e) {
            System.err.println("Failed to uninstall: " + e.getMessage());
            return false;
        }
    }

    private static Path getInstallDir() {
        return Paths.get(System.getProperty("user.home"), ".local/bin");
    }

    @Override
    public String[] getFormats() {
        return new String[] { ".appimage" };
    }


}
