package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.Output;

// mutable provider config shared by the subcommands
final class ProviderStore {
    private final CmdParser cmdParser;
    private final StateStore stateStore;
    private GrmConfig toolConfig;
    private ProviderRegistry providers;

    ProviderStore(
        CmdParser cmdParser,
        StateStore stateStore,
        GrmConfig toolConfig,
        ProviderRegistry providers) {
        this.cmdParser = cmdParser;
        this.stateStore = stateStore;
        this.toolConfig = toolConfig;
        this.providers = providers;
    }

    CmdParser cmdParser() {
        return cmdParser;
    }

    StateStore stateStore() {
        return stateStore;
    }

    ProviderRegistry providers() {
        return providers;
    }

    Map<String, ProviderConfig> currentProviders() {
        return toolConfig.providers() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(toolConfig.providers());
    }

    boolean saveProviders(Map<String, ProviderConfig> map) {
        GrmConfig updated = toolConfig.withProviders(map);
        try {
            stateStore.saveConfig(updated);
        } catch (IOException e) {
            Output.error("Failed to save providers: " + e.getMessage());
            return false;
        }
        toolConfig = updated;
        providers = new ProviderRegistry(map);
        return true;
    }
}
