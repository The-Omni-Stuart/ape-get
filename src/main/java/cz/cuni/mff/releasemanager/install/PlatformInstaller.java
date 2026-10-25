package cz.cuni.mff.releasemanager.install;

import java.nio.file.Path;

import cz.cuni.mff.releasemanager.platform.InstallRequest;
import cz.cuni.mff.releasemanager.platform.PackageKind;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

// hands the download to the platform and cleans the temp file
public final class PlatformInstaller {
    private final PlatformHandler platformHandler;

    public PlatformInstaller(PlatformHandler platformHandler) {
        this.platformHandler = platformHandler;
    }

    public Path install(
        Path downloaded,
        RepoRef ref,
        String version,
        PackageKind kind,
        String command,
        boolean assimilate,
        boolean desktop,
        String icon,
        boolean terminal,
        boolean noDesktop) {
        Path installed = null;
        try {
            installed = platformHandler.install(new InstallRequest(
                downloaded, ref.provider(), ref.path(), version,
                kind, command, assimilate, desktop, icon, terminal, noDesktop));
        } finally {
            if (!downloaded.equals(installed)) {
                StagedAsset.discard(downloaded);
            }
        }
        return installed;
    }
}
