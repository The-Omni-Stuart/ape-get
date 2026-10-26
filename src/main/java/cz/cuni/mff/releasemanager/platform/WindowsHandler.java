package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Path;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.StateStore;

// Windows installs via msiexec/NSIS or a portable executable shim
public final class WindowsHandler extends PlatformHandler {

    private static WindowsHandler instance;

    private final WindowsPortableInstaller portable;
    private final WindowsNativeInstaller nativeInstaller;

    private WindowsHandler() {
        this.portable = new WindowsPortableInstaller(this);
        this.nativeInstaller = new WindowsNativeInstaller(this);
    }

    WindowsHandler(StateStore stateStore) {
        super(stateStore);
        this.portable = new WindowsPortableInstaller(this);
        this.nativeInstaller = new WindowsNativeInstaller(this);
    }

    // singleton
    public static WindowsHandler getInstance() {
        if (instance == null) {
            instance = new WindowsHandler();
        }
        return instance;
    }

    @Override
    public Path install(InstallRequest request) {
        if (!supportsKind(request, Platform.OS.WINDOWS, "Windows")) {
            return null;
        }
        return request.kind().isPortable()
            ? portable.install(request)
            : nativeInstaller.install(request);
    }

    @Override
    public Path launcherPath(String provider, String repoFullName, String command) {
        return portable.launcherPath(provider, repoFullName, command);
    }

    @Override
    public boolean uninstallPortable(AppManifest manifest) {
        return portable.uninstall(manifest);
    }

    @Override
    public void activateRestored(AppManifest manifest, Path restoredPath) {
        portable.activateRestored(manifest, restoredPath);
    }

    @Override
    public boolean uninstall(Path asset) {
        return nativeInstaller.uninstall(asset);
    }

    @Override
    public boolean uninstall(Path asset, String productCode) {
        return nativeInstaller.uninstall(asset, productCode);
    }

    @Override
    public String productCode(Path installed) {
        return nativeInstaller.productCode(installed);
    }

    @Override
    public InstallState installState(AppManifest manifest) {
        return nativeInstaller.installState(manifest);
    }

    InstallState productInstallState(String productCode) {
        return nativeInstaller.productInstallState(productCode);
    }

    @Override
    public boolean supportsInstallerRollback(AppManifest manifest) {
        return nativeInstaller.supportsInstallerRollback(manifest);
    }

    @Override
    public boolean supportsBackup(AppManifest manifest) {
        return nativeInstaller.supportsInstallerRollback(manifest);
    }

    @Override
    public boolean rollbackInstaller(
        AppManifest current, AppManifest restored, Path installer) {
        return nativeInstaller.rollbackInstaller(current, restored, installer);
    }

    @Override
    public String[] getFormats() {
        return new String[] {".msi", ".exe", ".ape", ".com"};
    }

    @Override
    public Path getInstallRoot() {
        return configuredRoot(WindowsSupport.envPath("ProgramFiles", null));
    }

    static boolean isMsi(String fileName) {
        return WindowsSupport.isMsi(fileName);
    }

    static boolean isExe(String fileName) {
        return WindowsSupport.isExe(fileName);
    }
}
