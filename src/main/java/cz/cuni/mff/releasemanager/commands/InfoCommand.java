package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ManifestViews;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.RepoParser;

// shows everything we know about one installed application
public final class InfoCommand {
    private final CmdParser cmdParser;
    private final StateStore stateStore;
    private final PlatformHandler platformHandler;

    public InfoCommand(
        CmdParser cmdParser, StateStore stateStore, PlatformHandler platformHandler) {
        this.cmdParser = cmdParser;
        this.stateStore = stateStore;
        this.platformHandler = platformHandler;
    }

    public boolean run(Command command) {
        RepoRef ref = RepoParser.parseOrNull(command.argument);
        if (ref == null) {
            return false;
        }
        AppManifest manifest;
        try {
            var found = stateStore.findManifest(ref);
            if (found.isEmpty()) {
                Output.error("Release " + ref.display() + " is not found.");
                return false;
            }
            manifest = found.get();
        } catch (IOException e) {
            Output.error("Failed to load application state: " + e.getMessage());
            return false;
        }

        if (cmdParser.isJson()) {
            Output.json(new ManifestViews(platformHandler).infoEntry(manifest));
            return true;
        }

        Output.data("Repository: " + manifest.repo());
        Output.data("Provider: " + manifest.provider());
        Output.data("Version: "
            + (manifest.version() == null ? "unknown" : manifest.version()));
        Output.data("Installed: " + manifest.installedAt());
        Output.data("Updated: " + manifest.updatedAt());
        Output.data("Path: " + manifest.install().path());
        Output.data("Install state: " + platformHandler.installState(manifest)
            .name().toLowerCase(Locale.ROOT));
        Output.data("Asset: " + manifest.asset().name());
        if (manifest.install().kind() != null) {
            Output.data("Kind: " + manifest.install().kind());
        }
        if (manifest.install().command() != null) {
            Output.data("Command: " + manifest.install().command());
        }
        if (manifest.install().desktopEntry() != null) {
            Output.data("Desktop entry: " + manifest.install().desktopEntry());
        }
        Output.data("Checksum: " + ManifestViews.describeVerification(manifest.verification()));
        Output.data("Signature: " + ManifestViews.describePinnedSignature(manifest.signature()));
        if (manifest.install().uninstall() != null) {
            String productCode = manifest.install().uninstall().productCode();
            if (productCode != null && !productCode.isBlank()) {
                Output.data("Product code: " + productCode);
            }
        }
        List<AppManifest.Backup> backups = manifest.backups() == null
            ? List.of()
            : manifest.backups();
        Output.data("Backups: " + backups.size());
        for (AppManifest.Backup backup : backups) {
            Output.data("  - "
                + (backup.version() == null ? "unknown" : backup.version())
                + " (" + backup.createdAt() + ") " + backup.path());
        }
        return true;
    }
}
