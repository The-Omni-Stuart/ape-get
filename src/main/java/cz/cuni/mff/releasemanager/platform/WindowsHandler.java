package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import cz.cuni.mff.releasemanager.utils.FileUtils;

/**
 * WindowsHandler is responsible for handling the installation and uninstallation of applications on Windows systems.
 */
public final class WindowsHandler extends PlatformHandler {

    private static WindowsHandler instance;

    private WindowsHandler() {}

    /**
     * @return  Singleton instance of WindowsHandler.
     */
    public static WindowsHandler getInstance() {
        if (instance == null) {
            instance = new WindowsHandler();
        }
        return instance;
    }

    private static Path getInstallerStorage() {
        String programData = System.getenv("ProgramData");
        if (programData == null) {
            throw new IllegalStateException("ProgramData is not set");
        }
        return Paths.get(programData, APP_DATA_DIR);
    }

    private static boolean isAllowedUninstallPath(Path asset) {
        return isUnder(asset, envPath("ProgramFiles", null))
            || isUnder(asset, envPath("ProgramFiles(x86)", null))
            || isUnder(asset, envPath("ProgramData", APP_DATA_DIR))
            || isUnder(asset, envPath("LOCALAPPDATA", "Programs"));
    }

    private static Path envPath(String name, String child) {
        String value = System.getenv(name);
        if (value == null) {
            return null;
        }
        return child == null ? Paths.get(value) : Paths.get(value, child);
    }

    @Override
    public Path install(Path assetPath, String repoFullName) {
        try {
            String fileName = assetPath.getFileName().toString();
            if (fileName.endsWith(".msi")) {
                return installMsi(assetPath);
            } else if (fileName.endsWith(".exe")) {
                return installExe(assetPath, repoFullName);
            } else {
                System.err.println("Unsupported file format: " + fileName);
                return null;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("Installation error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Installs the MSI asset.
     * @param assetPath Path to the asset to install.
     * @return Path to the installed application which is then used for uninstallation.
     * @throws IOException
     * @throws InterruptedException
     */
    private Path installMsi(Path assetPath) throws IOException, InterruptedException {
        Process process = new ProcessBuilder()
            .command("msiexec", "/i", assetPath.toString())
            .inheritIO()
            .start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Installation failed with exit code: " + exitCode);
        }

        Path targetDir = getInstallerStorage();
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(assetPath.getFileName());
        Files.move(assetPath, target, StandardCopyOption.REPLACE_EXISTING);
        FileUtils.removeTempDir(assetPath);

        System.out.println("Installer cached at: " + target);
        return target;
    }

    /**
     * Installs the EXE asset.
     * @param assetPath Path to the asset to install.
     * @param repoFullName Repository in owner/name form.
     * @return Path to the uninstallation file.
     * @throws IOException
     * @throws InterruptedException
     */
    private Path installExe(Path assetPath, String repoFullName) throws IOException, InterruptedException {
        String installName = FileUtils.getInstallName(repoFullName, assetPath);
        Path targetDir = Paths.get(System.getenv("ProgramFiles"), installName);
        if (Files.exists(targetDir) && isOwnedByAnotherRepo(targetDir, repoFullName)) {
            System.err.println("Refusing to overwrite " + targetDir + " owned by another release.");
            return null;
        }
        String command = String.format("\"%s\" /S /D=%s", assetPath.toString(), targetDir.toString());
        Process process = new ProcessBuilder("cmd.exe", "/c", command)
            .inheritIO()
            .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Installation failed with exit code: " + exitCode);
        }

        Files.deleteIfExists(assetPath);
        FileUtils.removeTempDir(assetPath);
        System.out.println("Installed to: " + targetDir.toString());

        Path uninstallPath = findUninstallPath(targetDir);
        return uninstallPath;
    }

    /**
     * Finds the uninstallation file in the target directory.
     * @param targetDir Path to the target directory.
     * @return Path to the uninstallation file.
     * @throws IOException
     */
    private Path findUninstallPath(Path targetDir) throws IOException {
        return Files.walk(targetDir)
            .filter(path -> {
                String fileName = path.getFileName().toString();
                if (!Files.isDirectory(path)) {
                    return fileName.contains("uninst") && fileName.endsWith(".exe");
                }
                return false;
            })
            .findFirst()
            .orElseThrow(() ->new IOException("Uninstall path not found in: " + targetDir.toString()));
    }

    @Override
    public boolean uninstall(Path asset) {
        if (!isAllowedUninstallPath(asset)) {
            System.err.println("Refusing to uninstall path outside install directories: " + asset);
            return false;
        }
        try {
            String fileName = asset.getFileName().toString();
            if (fileName.endsWith(".msi")) {
                uninstallMsi(asset);
                return true;
            } else if (fileName.endsWith(".exe")) {
                uninstallExe(asset);
                return true;
            } else {
                System.err.println("Unsupported file format: " + fileName);
                return false;
            }
        } catch (InterruptedException | IOException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("Uninstallation error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Uninstalls the EXE asset.
     * @param asset Path to the asset to uninstall.
     * @throws IOException
     * @throws InterruptedException
     */
    private void uninstallExe(Path asset) throws IOException, InterruptedException {
        Process process = new ProcessBuilder()
            .command("cmd.exe", "/c", asset.toString(), "/S")
            .inheritIO()
            .start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Uninstallation failed with exit code: " + exitCode);
        }
    }

    /**
     * Uninstalls the MSI asset.
     * @param asset Path to the asset to uninstall.
     * @throws IOException
     * @throws InterruptedException
     */
    private void uninstallMsi(Path asset) throws IOException, InterruptedException {
        Process process = new ProcessBuilder()
            .command("msiexec", "/x", asset.toString())
            .inheritIO()
            .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Uninstallation failed with exit code: " + exitCode);
        }
        Files.deleteIfExists(asset);
        FileUtils.removeTempDir(asset);
    }

    @Override
    public String[] getFormats() {
        return new String[] {".msi", ".exe"};
    }

}
