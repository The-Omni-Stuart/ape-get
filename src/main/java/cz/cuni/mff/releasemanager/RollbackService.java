package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;

// restores the newest backup over the live install
public final class RollbackService {

    private final Platform.OS os;
    private final CmdParser cmdParser;
    private final StateStore stateStore;
    private final BackupManager backupManager;
    private final PlatformHandler platformHandler;
    private final Function<String, RepoRef> parseRepo;
    private final Function<AppManifest, Boolean> recordManifest;
    private final Predicate<String> confirm;
    private final Consumer<Object> printJson;

    public RollbackService(
        Platform.OS os,
        CmdParser cmdParser,
        StateStore stateStore,
        BackupManager backupManager,
        PlatformHandler platformHandler,
        Function<String, RepoRef> parseRepo,
        Function<AppManifest, Boolean> recordManifest,
        Predicate<String> confirm,
        Consumer<Object> printJson) {
        this.os = os;
        this.cmdParser = cmdParser;
        this.stateStore = stateStore;
        this.backupManager = backupManager;
        this.platformHandler = platformHandler;
        this.parseRepo = parseRepo;
        this.recordManifest = recordManifest;
        this.confirm = confirm;
        this.printJson = printJson;
    }

    public boolean rollback(Command command) {
        RepoRef ref = parseRepo.apply(command.argument);
        if (ref == null) {
            return false;
        }

        AppManifest current;
        try {
            var found = stateStore.findManifest(ref);
            if (found.isEmpty()) {
                System.err.println("Release " + ref.display() + " is not found.");
                return false;
            }
            current = found.get();
        } catch (IOException e) {
            System.err.println("Failed to load application state: " + e.getMessage());
            return false;
        }

        if (os == Platform.OS.WINDOWS
            && !platformHandler.supportsInstallerRollback(current)) {
            System.err.println("Rollback on Windows requires an MSI with a cached installer.");
            return false;
        }

        var latestBackup = backupManager.latest(current);
        if (latestBackup.isEmpty()) {
            System.err.println("No backups are available for " + ref.display() + ".");
            return false;
        }
        AppManifest.Backup backup = latestBackup.get();
        if (cmdParser.isDryRun()) {
            if (cmdParser.isJson()) {
                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("dry_run", true);
                plan.put("action", "rollback");
                plan.put("provider", ref.provider());
                plan.put("repo", ref.path());
                plan.put("from", current.version());
                plan.put("to", backup.version());
                plan.put("backup", backup.artifact());
                printJson.accept(plan);
            } else {
                System.out.println("Dry run: would roll back " + ref.display() + " from "
                    + (current.version() == null ? "unknown" : current.version())
                    + " to " + (backup.version() == null ? "unknown" : backup.version()) + ".");
                System.out.println("  Backup: " + backup.artifact());
                System.out.println("No changes were made.");
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
        if (!confirm.test(message)) {
            System.err.println("Rollback declined.");
            return false;
        }

        AppManifest restored;
        Path restoreTarget;
        try {
            restored = backupManager.readManifest(backup);
            if (restored == null || !restored.isValid()) {
                System.err.println("Backup manifest is invalid.");
                return false;
            }
            restoreTarget = backup.installPath() == null || backup.installPath().isBlank()
                ? Path.of(current.install().path())
                : Path.of(backup.installPath());
            backupManager.restore(backup, restoreTarget);
            platformHandler.activateRestored(restored, restoreTarget);
        } catch (IOException e) {
            System.err.println("Rollback failed: " + e.getMessage());
            return false;
        }

        AppManifest rolledBack = rolledBackManifest(restored, current);
        if (!recordManifest.apply(rolledBack)) {
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
            System.err.println("Rollback failed: " + e.getMessage());
            return false;
        }
        if (restored == null || !restored.isValid()) {
            System.err.println("Backup manifest is invalid.");
            return false;
        }
        if (!platformHandler.rollbackInstaller(
            current, restored, Path.of(backup.artifact()))) {
            System.err.println("Rollback failed; " + ref.display()
                + " may need to be reinstalled.");
            return false;
        }
        if (!recordManifest.apply(rolledBackManifest(restored, current))) {
            return false;
        }
        printRollbackResult(ref, backup);
        return true;
    }

    private void printRollbackResult(RepoRef ref, AppManifest.Backup backup) {
        if (cmdParser.isJson()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider", ref.provider());
            result.put("repo", ref.path());
            result.put("version", backup.version());
            printJson.accept(Map.of("rolled_back", result));
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
