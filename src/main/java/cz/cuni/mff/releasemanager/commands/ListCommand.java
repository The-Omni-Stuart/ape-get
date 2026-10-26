package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.json.JsonViews.AppList;
import cz.cuni.mff.releasemanager.json.JsonViews.AppView;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.Output;

// lists every tracked application
public final class ListCommand {
    private final CmdParser cmdParser;
    private final StateStore stateStore;
    private final PlatformHandler platformHandler;

    public ListCommand(
        CmdParser cmdParser, StateStore stateStore, PlatformHandler platformHandler) {
        this.cmdParser = cmdParser;
        this.stateStore = stateStore;
        this.platformHandler = platformHandler;
    }

    public boolean run() {
        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            Output.error("Failed to load applications: " + e.getMessage());
            return false;
        }
        if (manifests.isEmpty() && !cmdParser.isJson()) {
            Output.data("No releases installed.");
            return true;
        }
        if (cmdParser.isJson()) {
            ManifestViews views = new ManifestViews(platformHandler);
            List<AppView> apps = new ArrayList<>();
            for (AppManifest manifest : manifests) {
                apps.add(views.listEntry(manifest));
            }
            Output.json(new AppList(apps));
            return true;
        }
        for (AppManifest manifest : manifests) {
            String version = manifest.version() == null ? "unknown" : manifest.version();
            Output.data(manifest.provider() + ":" + manifest.repo() + " " + version);
        }
        return true;
    }
}
