package cz.cuni.mff.releasemanager.install;

import java.io.IOException;
import java.util.List;

import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.GrmConfig;

// creates the pre-update backup when the platform supports it
public final class BackupService {
    private final Platform.OS os;
    private final GrmConfig toolConfig;
    private final BackupManager backupManager;
    private final PlatformHandler platformHandler;

    public BackupService(
        Platform.OS os,
        GrmConfig toolConfig,
        BackupManager backupManager,
        PlatformHandler platformHandler) {
        this.os = os;
        this.toolConfig = toolConfig;
        this.backupManager = backupManager;
        this.platformHandler = platformHandler;
    }

    public List<AppManifest.Backup> forUpdate(AppManifest previous) throws IOException {
        List<AppManifest.Backup> backups = previous.backups() == null
            ? List.of()
            : previous.backups();
        boolean supported = os != Platform.OS.WINDOWS
            || platformHandler.supportsInstallerRollback(previous);
        if (toolConfig.backupCount() > 0 && supported) {
            return backupManager.createAndPrune(previous, toolConfig.backupCount());
        }
        return backups;
    }
}
