package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;

import cz.cuni.mff.releasemanager.utils.ChildEnvironment;
import cz.cuni.mff.releasemanager.utils.ProcessRunner;

// small Windows helpers shared by the portable and native installers
final class WindowsSupport {

    private WindowsSupport() {}

    static Path getInstallerStorage() {
        String programData = System.getenv("ProgramData");
        if (programData == null) {
            throw new IllegalStateException("ProgramData is not set");
        }
        return Paths.get(programData, PlatformHandler.APP_DATA_DIR);
    }

    static Path envPath(String name, String child) {
        String value = System.getenv(name);
        if (value == null) {
            return null;
        }
        return child == null ? Paths.get(value) : Paths.get(value, child);
    }

    static Path portableBinDir() {
        Path base = envPath("LOCALAPPDATA", PlatformHandler.APP_DATA_DIR);
        if (base == null) {
            base = Paths.get(System.getProperty("user.home"), ".local", "bin");
            return base;
        }
        return base.resolve("bin");
    }

    static ProcessBuilder process(String... command) {
        return ChildEnvironment.sanitize(new ProcessBuilder(command));
    }

    static String runPowerShell(String script)
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

    static boolean isAllowedUninstallPath(PlatformHandler handler, Path asset) {
        return PlatformHandler.isUnder(asset, handler.getInstallRoot())
            || PlatformHandler.isUnder(asset, envPath("ProgramFiles", null))
            || PlatformHandler.isUnder(asset, envPath("ProgramFiles(x86)", null))
            || PlatformHandler.isUnder(asset, envPath("ProgramData", PlatformHandler.APP_DATA_DIR))
            || PlatformHandler.isUnder(asset, envPath("LOCALAPPDATA", "Programs"))
            || PlatformHandler.isUnder(asset, handler.statePaths().root())
            || PlatformHandler.isUnder(asset, portableBinDir());
    }

    static boolean isMsi(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".msi");
    }

    static boolean isExe(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".exe");
    }
}
