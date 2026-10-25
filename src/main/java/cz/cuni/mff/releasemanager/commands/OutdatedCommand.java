package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.install.UpdateChecker;
import cz.cuni.mff.releasemanager.json.JsonViews.OutdatedEntry;
import cz.cuni.mff.releasemanager.json.JsonViews.OutdatedList;
import cz.cuni.mff.releasemanager.provider.UpdateChannel;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.Output;

// checks installed packages without changing anything
public final class OutdatedCommand {
    private final CmdParser cmdParser;
    private final StateStore stateStore;
    private final UpdateChecker checker;
    private final UpdateChannel defaultChannel;

    public OutdatedCommand(
        CmdParser cmdParser,
        StateStore stateStore,
        UpdateChecker checker,
        UpdateChannel defaultChannel) {
        this.cmdParser = cmdParser;
        this.stateStore = stateStore;
        this.checker = checker;
        this.defaultChannel = defaultChannel;
    }

    public boolean run() {
        UpdateChannel channel;
        try {
            String value = cmdParser.getChannel();
            channel = value == null || value.isBlank()
                ? defaultChannel : UpdateChannel.parse(value);
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return false;
        }
        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            Output.error("Failed to load applications: " + e.getMessage());
            return false;
        }
        UpdateChecker.Report report = checker.check(manifests, channel);
        if (cmdParser.isJson()) {
            Output.json(new OutdatedList(report.outdated()));
            return !report.failed();
        }
        if (report.outdated().isEmpty()) {
            Output.data(report.failed()
                ? "Could not check every package."
                : "Everything is up to date.");
            return !report.failed();
        }
        for (OutdatedEntry entry : report.outdated()) {
            Output.data(entry.provider() + ":" + entry.repo() + " "
                + label(entry.current()) + " -> " + label(entry.latest()));
        }
        if (report.failed()) {
            Output.error("Could not check every package.");
        }
        return !report.failed();
    }

    private static String label(String version) {
        return version == null ? "unknown" : version;
    }
}
