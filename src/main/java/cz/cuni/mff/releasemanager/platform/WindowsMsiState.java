package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.utils.Output;

// reads MSI product codes and registry presence
final class WindowsMsiState {
    private static final Pattern MSI_PRODUCT_CODE = Pattern.compile(
        "\\{?[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\\}?");

    private final PlatformHandler handler;

    WindowsMsiState(PlatformHandler handler) {
        this.handler = handler;
    }

    String productCode(Path installed) {
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
            String output = WindowsSupport.runPowerShell(script);
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

    PlatformHandler.InstallState installState(AppManifest manifest) {
        String code = manifest != null && manifest.install() != null
            && manifest.install().uninstall() != null
            ? manifest.install().uninstall().productCode()
            : null;
        if (code == null || code.isBlank()) {
            return handler.defaultInstallState(manifest);
        }
        return productInstallState(code);
    }

    PlatformHandler.InstallState productInstallState(String productCode) {
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
            String output = WindowsSupport.runPowerShell(script);
            if (output.lines().anyMatch(line -> line.strip().equals("installed"))) {
                return PlatformHandler.InstallState.INSTALLED;
            }
            if (output.lines().anyMatch(line -> line.strip().equals("absent"))) {
                return PlatformHandler.InstallState.MISSING;
            }
            return PlatformHandler.InstallState.UNKNOWN;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error(
                "Warning: could not read the MSI product state: " + e.getMessage());
            return PlatformHandler.InstallState.UNKNOWN;
        }
    }

    boolean supportsInstallerRollback(AppManifest manifest) {
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
}
