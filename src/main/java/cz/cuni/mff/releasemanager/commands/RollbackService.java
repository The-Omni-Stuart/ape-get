package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.Confirmer;
import cz.cuni.mff.releasemanager.json.JsonViews.RollbackPlan;
import cz.cuni.mff.releasemanager.json.JsonViews.RollbackResult;
import cz.cuni.mff.releasemanager.json.JsonViews.RolledBack;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.ManifestStore;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.RepoParser;

// restores the newest backup over the live install
public final class RollbackService {

    private final Platform.OS os;
    private final CmdParser cmdParser;
    private final ManifestStore manifests;
    private final BackupManager backupManager;
    private final PlatformHandler platformHandler;
    private final Confirmer confirm;

    public RollbackService(
        Platform.OS os,
        CmdParser cmdParser,
        ManifestStore manifests,
        BackupManager backupManager,
        PlatformHandler platformHandler,
        Confirmer confirm) {
        this.os = os;
        this.cmdParser = cmdParser;
        this.manifests = manifests;
        this.backupManager = backupManager;
        this.platformHandler = platformHandler;
        this.confirm = confirm;
    }

    public boolean rollback(Command command) {
        RepoRef ref = RepoParser.parseOrNull(command.argument);
        if (ref == null) {
            return false;
        }

        AppManifest current;
        try {
            var found = manifests.find(ref);
            if (found.isEmpty()) {
                Output.error("Release " + ref.display() + " is not found.");
                return false;
            }
            current = found.get();
        } catch (IOException e) {
            Output.error("Failed to load application state: " + e.getMessage());
            return false;
        }

        if (os == Platform.OS.WINDOWS
            && !platformHandler.supportsInstallerRollback(current)) {
            Output.error("Rollback on Windows requires an MSI with a cached installer.");
            return false;
        }

        var latestBackup = backupManager.latest(current);
        if (latestBackup.isEmpty()) {
            Output.error("No backups are available for " + ref.display() + ".");
            return false;
        }
        AppManifest.Backup backup = latestBackup.get();
        if (cmdParser.isDryRun()) {
            if (cmdParser.isJson()) {
                Output.json(new RollbackPlan(
                    true,
                    "rollback",
                    ref.provider(),
                    ref.path(),
                    current.version(),
                    backup.version(),
                    backup.artifact()));
            } else {
                Output.data("Dry run: would roll back " + ref.display() + " from "
                    + (current.version() == null ? "unknown" : current.version())
                    + " to " + (backup.version() == null ? "unknown" : backup.version()) + ".");
                Output.data("  Backup: " + backup.artifact());
                Output.data("No changes were made.");
            }
            return true;
        }

        if (os == Platform.OS.WINDOWS) {
            return rollbackWindows(current, backup, ref);
        }

        String message = "Roll back " + ref.display() + " from "
            + (current.version() == null ? "unknown" : current.version())
            + " to " + (backup.version() == null ? "unknown" : backup.version())
            + "?\nThis will replace:\n  " + current.install().path()
            + "\nwith the backup at:\n  " + backup.artifact();
        if (!confirm.confirm(message)) {
            Output.error("Rollback declined.");
            return false;
        }

        AppManifest restored;
        Path restoreTarget;
        try {
            restored = backupManager.readManifest(backup);
            if (restored == null || !restored.isValid()) {
                Output.error("Backup manifest is invalid.");
                return false;
            }
            restoreTarget = backup.installPath() == null || backup.installPath().isBlank()
                ? Path.of(current.install().path())
                : Path.of(backup.installPath());
            backupManager.restore(backup, restoreTarget);
            platformHandler.activateRestored(restored, restoreTarget);
        } catch (IOException e) {
            Output.error("Rollback failed: " + e.getMessage());
            return false;
        }

        AppManifest rolledBack = rolledBackManifest(restored, current);
        if (!manifests.save(rolledBack)) {
            return false;
        }
        printRollbackResult(ref, backup);
        return true;
    }

    private boolean rollbackWindows(
        AppManifest current, AppManifest.Backup backup, RepoRef ref) {
        AppManifest restored;
        try {
            restored = backupManager.readManifest(backup);
        } catch (IOException e) {
            Output.error("Rollback failed: " + e.getMessage());
            return false;
        }
        if (restored == null || !restored.isValid()) {
            Output.error("Backup manifest is invalid.");
            return false;
        }
        if (!platformHandler.rollbackInstaller(
            current, restored, Path.of(backup.artifact()))) {
            Output.error("Rollback failed; " + ref.display()
                + " may need to be reinstalled.");
            return false;
        }
        if (!manifests.save(rolledBackManifest(restored, current))) {
            return false;
        }
        printRollbackResult(ref, backup);
        return true;
    }

    private void printRollbackResult(RepoRef ref, AppManifest.Backup backup) {
        if (cmdParser.isJson()) {
            Output.json(new RolledBack(new RollbackResult(
                ref.provider(), ref.path(), backup.version())));
        } else {
            Output.info("Rolled back " + ref.display() + " to "
                + (backup.version() == null ? "unknown" : backup.version()) + ".");
        }
    }

    private static AppManifest rolledBackManifest(
        AppManifest restored, AppManifest current) {
        return new AppManifest(
            restored.kind(),
            restored.schemaVersion(),
            restored.provider(),
            restored.repo(),
            restored.version(),
            restored.releaseId(),
            restored.asset(),
            restored.install(),
            restored.verification(),
            current.backups() == null ? List.of() : current.backups(),
            restored.installedAt(),
            Instant.now(),
            restored.signature(),
            restored.providerType(),
            restored.providerApi());
    }
}
