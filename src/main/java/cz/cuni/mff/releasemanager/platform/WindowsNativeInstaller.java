package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;

// MSI and NSIS install, uninstall, state, and rollback
final class WindowsNativeInstaller {
    private final PlatformHandler handler;
    private final WindowsMsiState msiState;

    WindowsNativeInstaller(PlatformHandler handler) {
        this.handler = handler;
        this.msiState = new WindowsMsiState(handler);
    }

    Path install(InstallRequest request) {
        try {
            Path assetPath = request.asset();
            String fileName = assetPath.getFileName().toString();
            if (WindowsSupport.isMsi(fileName)) {
                return installMsi(assetPath, request.provider(), request.repoFullName());
            }
            if (WindowsSupport.isExe(fileName)) {
                return installExe(assetPath, request.provider(), request.repoFullName());
            }
            Output.error("Unsupported file format: " + fileName);
            return null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error("Installation error: " + e.getMessage());
            return null;
        }
    }

    // caches the MSI under the provider-qualified install name for uninstall and rollback
    private Path installMsi(Path assetPath, String provider, String repoFullName)
        throws IOException, InterruptedException {
        Process process = WindowsSupport.process()
            .command("msiexec", "/i", assetPath.toString(), "/qn", "/norestart")
            .inheritIO()
            .start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Installation failed with exit code: " + exitCode);
        }

        Path targetDir = WindowsSupport.getInstallerStorage()
            .resolve(FileUtils.installName(provider, repoFullName));
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(assetPath.getFileName());
        Files.move(assetPath, target, StandardCopyOption.REPLACE_EXISTING);
        FileUtils.removeTempDir(assetPath);

        Output.info("Installer cached at: " + target);
        return target;
    }

    // NSIS installer with /D= must be the last argument and stays unquoted
    private Path installExe(Path assetPath, String provider, String repoFullName)
        throws IOException, InterruptedException {
        String installName = FileUtils.installName(provider, repoFullName);
        Path installRoot = handler.getInstallRoot();
        if (installRoot == null) {
            throw new IOException("Cannot determine the Windows install root.");
        }
        Path targetDir = installRoot.resolve(installName);
        if (Files.exists(targetDir)
            && handler.isOwnedByAnotherRepo(targetDir, provider, repoFullName)) {
            Output.error("Refusing to overwrite " + targetDir + " owned by another release.");
            return null;
        }
        String command = String.format("\"%s\" /S /D=%s", assetPath.toString(), targetDir.toString());
        Process process = WindowsSupport.process("cmd.exe", "/c", command)
            .inheritIO()
            .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Installation failed with exit code: " + exitCode);
        }

        Files.deleteIfExists(assetPath);
        FileUtils.removeTempDir(assetPath);
        Output.info("Installed to: " + targetDir.toString());

        return findUninstallPath(targetDir);
    }

    // first uninstall*.exe anywhere under the install directory
    private Path findUninstallPath(Path targetDir) throws IOException {
        try (var paths = Files.walk(targetDir)) {
            return paths
                .filter(path -> !Files.isDirectory(path))
                .filter(path -> {
                    String fileName = path.getFileName().toString();
                    return fileName.toLowerCase(Locale.ROOT).contains("uninst")
                        && WindowsSupport.isExe(fileName);
                })
                .findFirst()
                .orElseThrow(() -> new IOException(
                    "Uninstall path not found in: " + targetDir));
        }
    }

    boolean uninstall(Path asset) {
        if (!WindowsSupport.isAllowedUninstallPath(handler, asset)) {
            Output.error("Refusing to uninstall path outside install directories: " + asset);
            return false;
        }
        if (!Files.exists(asset)) {
            return true;
        }
        try {
            String fileName = asset.getFileName().toString();
            if (WindowsSupport.isMsi(fileName)) {
                uninstallMsi(asset);
                return true;
            }
            if (WindowsSupport.isExe(fileName)) {
                uninstallExe(asset);
                return true;
            }
            Output.error("Unsupported file format: " + fileName);
            return false;
        } catch (InterruptedException | IOException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error("Uninstallation error: " + e.getMessage());
            return false;
        }
    }

    boolean uninstall(Path asset, String productCode) {
        if (productCode == null || productCode.isBlank()) {
            return uninstall(asset);
        }
        try {
            if (asset != null && !WindowsSupport.isAllowedUninstallPath(handler, asset)) {
                Output.error("Refusing to uninstall path outside install directories: " + asset);
                return false;
            }
            if (productInstallState(productCode) == PlatformHandler.InstallState.MISSING) {
                if (asset != null) {
                    Files.deleteIfExists(asset);
                    FileUtils.removeTempDir(asset);
                }
                return true;
            }
            Process process = WindowsSupport.process(
                "msiexec", "/x", productCode, "/qn", "/norestart")
                .inheritIO()
                .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Uninstallation failed with exit code: " + exitCode);
            }
            if (asset != null) {
                Files.deleteIfExists(asset);
                FileUtils.removeTempDir(asset);
            }
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error("Uninstallation error: " + e.getMessage());
            return false;
        }
    }

    String productCode(Path installed) {
        return msiState.productCode(installed);
    }

    PlatformHandler.InstallState installState(AppManifest manifest) {
        return msiState.installState(manifest);
    }

    PlatformHandler.InstallState productInstallState(String productCode) {
        return msiState.productInstallState(productCode);
    }

    boolean supportsInstallerRollback(AppManifest manifest) {
        return msiState.supportsInstallerRollback(manifest);
    }

    boolean rollbackInstaller(
        AppManifest current, AppManifest restored, Path installer) {
        if (restored == null || restored.install() == null
            || restored.install().path() == null
            || installer == null || !Files.isRegularFile(installer)) {
            Output.error("Rollback installer is missing: " + installer);
            return false;
        }
        try {
            if (installState(current) != PlatformHandler.InstallState.MISSING) {
                AppManifest.Uninstall uninstall = current.install().uninstall();
                String code = uninstall == null ? null : uninstall.productCode();
                boolean removed = code == null || code.isBlank()
                    ? uninstall(Path.of(current.install().path()))
                    : uninstall(Path.of(current.install().path()), code);
                if (!removed) {
                    Output.error(
                        "Rollback aborted: the current version could not be uninstalled.");
                    return false;
                }
            }

            Process process = WindowsSupport.process(
                "msiexec", "/i", installer.toString(), "/qn", "/norestart")
                .inheritIO()
                .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Rollback installation failed with exit code: "
                    + exitCode);
            }

            Path cached = Path.of(restored.install().path());
            if (!installer.toAbsolutePath().normalize()
                .equals(cached.toAbsolutePath().normalize())) {
                Files.createDirectories(cached.toAbsolutePath().getParent());
                Files.copy(installer, cached, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error("Rollback error: " + e.getMessage());
            return false;
        }
    }

    // run the NSIS uninstaller and wait for it to remove itself
    private void uninstallExe(Path asset) throws IOException, InterruptedException {
        Process process = WindowsSupport.process(asset.toString(), "/S")
            .inheritIO()
            .start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Uninstallation failed with exit code: " + exitCode);
        }

        // NSIS copies the uninstaller to a temp directory and may return before
        // the original process has finished removing itself and the install tree.
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (Files.exists(asset) && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        if (Files.exists(asset)) {
            throw new IOException("Uninstaller did not remove " + asset);
        }
    }

    // msiexec /x by cached file
    private void uninstallMsi(Path asset) throws IOException, InterruptedException {
        Process process = WindowsSupport.process()
            .command("msiexec", "/x", asset.toString(), "/qn", "/norestart")
            .inheritIO()
            .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Uninstallation failed with exit code: " + exitCode);
        }
        Files.deleteIfExists(asset);
        FileUtils.removeTempDir(asset);
    }
}
