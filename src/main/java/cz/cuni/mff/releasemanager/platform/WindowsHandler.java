package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.utils.ChildEnvironment;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.ProcessRunner;

// Windows installs via msiexec or an NSIS installer
public final class WindowsHandler extends PlatformHandler {

    private static final Pattern MSI_PRODUCT_CODE = Pattern.compile(
        "\\{?[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\\}?");

    private static WindowsHandler instance;

    private WindowsHandler() {}

    // singleton
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

    private boolean isAllowedUninstallPath(Path asset) {
        return isUnder(asset, getInstallRoot())
            || isUnder(asset, envPath("ProgramFiles", null))
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
    public Path install(InstallRequest request) {
        try {
            Path assetPath = request.asset();
            String fileName = assetPath.getFileName().toString();
            if (isMsi(fileName)) {
                return installMsi(assetPath, request.provider(), request.repoFullName());
            } else if (isExe(fileName)) {
                return installExe(assetPath, request.provider(), request.repoFullName());
            } else {
                Output.error("Unsupported file format: " + fileName);
                return null;
            }
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
        Process process = process()
            .command("msiexec", "/i", assetPath.toString(), "/qn", "/norestart")
            .inheritIO()
            .start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Installation failed with exit code: " + exitCode);
        }

        Path targetDir = getInstallerStorage()
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
        Path installRoot = getInstallRoot();
        if (installRoot == null) {
            throw new IOException("Cannot determine the Windows install root.");
        }
        Path targetDir = installRoot.resolve(installName);
        if (Files.exists(targetDir)
            && isOwnedByAnotherRepo(targetDir, provider, repoFullName)) {
            Output.error("Refusing to overwrite " + targetDir + " owned by another release.");
            return null;
        }
        String command = String.format("\"%s\" /S /D=%s", assetPath.toString(), targetDir.toString());
        Process process = process("cmd.exe", "/c", command)
            .inheritIO()
            .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Installation failed with exit code: " + exitCode);
        }

        Files.deleteIfExists(assetPath);
        FileUtils.removeTempDir(assetPath);
        Output.info("Installed to: " + targetDir.toString());

        Path uninstallPath = findUninstallPath(targetDir);
        return uninstallPath;
    }

    // first uninstall*.exe anywhere under the install directory
    private Path findUninstallPath(Path targetDir) throws IOException {
        try (var paths = Files.walk(targetDir)) {
            return paths
                .filter(path -> !Files.isDirectory(path))
                .filter(path -> {
                    String fileName = path.getFileName().toString();
                    return fileName.toLowerCase(Locale.ROOT).contains("uninst")
                        && isExe(fileName);
                })
                .findFirst()
                .orElseThrow(() -> new IOException(
                    "Uninstall path not found in: " + targetDir));
        }
    }

    @Override
    public boolean uninstall(Path asset) {
        if (!isAllowedUninstallPath(asset)) {
            Output.error("Refusing to uninstall path outside install directories: " + asset);
            return false;
        }
        if (!Files.exists(asset)) {
            return true;
        }
        try {
            String fileName = asset.getFileName().toString();
            if (isMsi(fileName)) {
                uninstallMsi(asset);
                return true;
            } else if (isExe(fileName)) {
                uninstallExe(asset);
                return true;
            } else {
                Output.error("Unsupported file format: " + fileName);
                return false;
            }
        } catch (InterruptedException | IOException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error("Uninstallation error: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean uninstall(Path asset, String productCode) {
        if (productCode == null || productCode.isBlank()) {
            return uninstall(asset);
        }
        try {
            if (asset != null && !isAllowedUninstallPath(asset)) {
                Output.error("Refusing to uninstall path outside install directories: " + asset);
                return false;
            }
            if (productInstallState(productCode) == InstallState.MISSING) {
                if (asset != null) {
                    Files.deleteIfExists(asset);
                    FileUtils.removeTempDir(asset);
                }
                return true;
            }
            Process process = process(
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

    @Override
    public String productCode(Path installed) {
        if (installed == null || installed.getFileName() == null
            || !installed.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".msi")) {
            return null;
        }

        String script = "$installer = New-Object -ComObject WindowsInstaller.Installer;"
            + "$db = $installer.GetType().InvokeMember('OpenDatabase','InvokeMethod',$null,"
            + "$installer,@('" + installed.toString().replace("'", "''") + "',0));"
            + "$view = $db.GetType().InvokeMember('OpenView','InvokeMethod',$null,$db,"
            + "@('SELECT Value FROM Property WHERE Property=''ProductCode'''));"
            + "$view.GetType().InvokeMember('Execute','InvokeMethod',$null,$view,$null);"
            + "$rec = $view.GetType().InvokeMember('Fetch','InvokeMethod',$null,$view,$null);"
            + "$rec.GetType().InvokeMember('StringData','GetProperty',$null,$rec,1)";
        try {
            String output = runPowerShell(script);
            Matcher matcher = MSI_PRODUCT_CODE.matcher(output);
            if (!matcher.find()) {
                return null;
            }
            String code = matcher.group();
            return code.startsWith("{") ? code : "{" + code + "}";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error("Warning: could not read the MSI product code: " + e.getMessage());
            return null;
        }
    }

    @Override
    public InstallState installState(AppManifest manifest) {
        String code = manifest != null && manifest.install() != null
            && manifest.install().uninstall() != null
            ? manifest.install().uninstall().productCode()
            : null;
        if (code == null || code.isBlank()) {
            return super.installState(manifest);
        }
        return productInstallState(code);
    }

    InstallState productInstallState(String productCode) {
        String code = productCode.replace("'", "''");
        String script = "$code = '" + code + "';"
            + "$paths = @("
            + "('HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\' + $code),"
            + "('HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\' + $code),"
            + "('HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\' + $code)"
            + ");"
            + "if ($paths | Where-Object { Test-Path -LiteralPath $_ })"
            + " { 'installed' } else { 'absent' }";
        try {
            String output = runPowerShell(script);
            if (output.lines().anyMatch(line -> line.strip().equals("installed"))) {
                return InstallState.INSTALLED;
            }
            if (output.lines().anyMatch(line -> line.strip().equals("absent"))) {
                return InstallState.MISSING;
            }
            return InstallState.UNKNOWN;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error(
                "Warning: could not read the MSI product state: " + e.getMessage());
            return InstallState.UNKNOWN;
        }
    }

    @Override
    public boolean supportsInstallerRollback(AppManifest manifest) {
        if (manifest == null || manifest.install() == null) {
            return false;
        }
        if (!"msi".equalsIgnoreCase(manifest.install().kind())) {
            return false;
        }
        String path = manifest.install().path();
        return path != null
            && path.toLowerCase(Locale.ROOT).endsWith(".msi")
            && Files.isRegularFile(Path.of(path));
    }

    @Override
    public boolean supportsBackup(AppManifest manifest) {
        return supportsInstallerRollback(manifest);
    }

    @Override
    public boolean rollbackInstaller(
        AppManifest current, AppManifest restored, Path installer) {
        if (restored == null || restored.install() == null
            || restored.install().path() == null
            || installer == null || !Files.isRegularFile(installer)) {
            Output.error("Rollback installer is missing: " + installer);
            return false;
        }
        try {
            if (installState(current) != InstallState.MISSING) {
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

            Process process = process(
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

    static boolean isMsi(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".msi");
    }

    static boolean isExe(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".exe");
    }

    private static ProcessBuilder process(String... command) {
        return ChildEnvironment.sanitize(new ProcessBuilder(command));
    }

    private static String runPowerShell(String script)
        throws IOException, InterruptedException {
        ProcessRunner.Result result = ProcessRunner.run(
            process("pwsh", "-NoProfile", "-NonInteractive", "-Command", script),
            Duration.ofSeconds(60));
        if (result.timedOut()) {
            throw new IOException("PowerShell timed out");
        }
        if (result.exitCode() != 0) {
            throw new IOException(result.output().strip());
        }
        return result.output();
    }

    // run the NSIS uninstaller and wait for it to remove itself
    private void uninstallExe(Path asset) throws IOException, InterruptedException {
        Process process = process(asset.toString(), "/S")
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
        Process process = process()
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

    @Override
    public String[] getFormats() {
        return new String[] {".msi", ".exe"};
    }

    @Override
    public Path getInstallRoot() {
        return configuredRoot(envPath("ProgramFiles", null));
    }

}
