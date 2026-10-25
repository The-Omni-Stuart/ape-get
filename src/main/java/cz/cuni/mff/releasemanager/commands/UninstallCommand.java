package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.Confirmation;
import cz.cuni.mff.releasemanager.install.UninstallService;
import cz.cuni.mff.releasemanager.json.JsonViews.UninstallPlan;
import cz.cuni.mff.releasemanager.json.JsonViews.UninstallResult;
import cz.cuni.mff.releasemanager.json.JsonViews.UninstalledResult;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.RepoParser;

// removes one install, its manifest, and its application state
public final class UninstallCommand {
    private final CmdParser cmdParser;
    private final StateStore stateStore;
    private final UninstallService uninstallService;
    private final Confirmation confirmation;

    public UninstallCommand(
        CmdParser cmdParser,
        StateStore stateStore,
        UninstallService uninstallService,
        Confirmation confirmation) {
        this.cmdParser = cmdParser;
        this.stateStore = stateStore;
        this.uninstallService = uninstallService;
        this.confirmation = confirmation;
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

        String uninstallPath = manifest.install().uninstall() != null
            && manifest.install().uninstall().path() != null
            ? manifest.install().uninstall().path()
            : manifest.install().path();
        if (cmdParser.isDryRun()) {
            if (cmdParser.isJson()) {
                Output.json(new UninstallPlan(
                    true, "uninstall", ref.provider(), ref.path(), uninstallPath));
            } else {
                Output.data("Dry run: would uninstall " + ref.display() + ".");
                Output.data("  Path: " + uninstallPath);
                Output.data("No changes were made.");
            }
            return true;
        }

        if (!confirmation.allowed("Uninstall",
            ref.display() + " " + manifest.version() + " from " + uninstallPath)) {
            return false;
        }
        if (!uninstallService.remove(manifest)) {
            return false;
        }
        try {
            stateStore.rebuildIndex();
        } catch (IOException e) {
            Output.error("Uninstalled " + ref.display()
                + ", but failed to rebuild the index: " + e.getMessage());
            return false;
        }
        if (cmdParser.isJson()) {
            Output.json(new UninstalledResult(
                new UninstallResult(ref.provider(), ref.path(), uninstallPath)));
        } else {
            Output.info("Successfully uninstalled.");
        }
        return true;
    }
}
