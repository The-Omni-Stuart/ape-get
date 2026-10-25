package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.utils.ChildEnvironment;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.FileUtils;

/**
 * WindowsHandler is responsible for handling the installation and uninstallation of applications on Windows systems.
 */
public final class WindowsHandler extends PlatformHandler {

    private static final Pattern MSI_PRODUCT_CODE = Pattern.compile(
        "\\{?[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\\}?");

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
        Process process = process()
            .command("msiexec", "/i", assetPath.toString(), "/qn", "/norestart")
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

        Output.info("Installer cached at: " + target);
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
        Path installRoot = getInstallRoot();
        if (installRoot == null) {
            throw new IOException("Cannot determine the Windows install root.");
        }
        Path targetDir = installRoot.resolve(installName);
        if (Files.exists(targetDir) && isOwnedByAnotherRepo(targetDir, repoFullName)) {
            System.err.println("Refusing to overwrite " + targetDir + " owned by another release.");
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

    @Override
    public boolean uninstall(Path asset, String productCode) {
        if (productCode == null || productCode.isBlank()) {
            return uninstall(asset);
        }
        try {
            if (asset != null && !isAllowedUninstallPath(asset)) {
                System.err.println("Refusing to uninstall path outside install directories: " + asset);
                return false;
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
            System.err.println("Uninstallation error: " + e.getMessage());
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
            System.err.println("Warning: could not read the MSI product code: " + e.getMessage());
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
            System.err.println(
                "Warning: could not read the MSI product state: " + e.getMessage());
            return InstallState.UNKNOWN;
        }
    }

    private static ProcessBuilder process(String... command) {
        return ChildEnvironment.sanitize(new ProcessBuilder(command));
    }

    private static String runPowerShell(String script)
        throws IOException, InterruptedException {
        Process process = process(
            "pwsh", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .start();
        String output = new String(
            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("PowerShell timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException(output.strip());
        }
        return output;
    }

    /**
     * Uninstalls the EXE asset.
     * @param asset Path to the asset to uninstall.
     * @throws IOException
     * @throws InterruptedException
     */
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

    /**
     * Uninstalls the MSI asset.
     * @param asset Path to the asset to uninstall.
     * @throws IOException
     * @throws InterruptedException
     */
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
