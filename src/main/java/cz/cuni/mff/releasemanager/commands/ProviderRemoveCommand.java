package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cz.cuni.mff.releasemanager.cmd.Confirmer;
import cz.cuni.mff.releasemanager.install.UninstallService;
import cz.cuni.mff.releasemanager.json.JsonViews.RemovedProvider;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.utils.Output;

// providers remove
final class ProviderRemoveCommand {
    private final ProviderStore store;
    private final Confirmer confirm;
    private final UninstallService uninstallService;

    ProviderRemoveCommand(
        ProviderStore store, Confirmer confirm, UninstallService uninstallService) {
        this.store = store;
        this.confirm = confirm;
        this.uninstallService = uninstallService;
    }

    boolean run(List<String> args) {
        ProviderOptionsParser.Options options;
        try {
            options = ProviderOptionsParser.parse(args,
                Set.of("abandon", "purge"), Set.of());
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return false;
        }
        if (options.positional().size() != 1) {
            return ProviderViews.usage();
        }
        if (options.flags().contains("abandon") && options.flags().contains("purge")) {
            Output.error("Use only one of --abandon or --purge.");
            return false;
        }
        String name = options.positional().get(0);
        ProviderConfig config = store.providers().get(name).orElse(null);
        if (config == null) {
            Output.error("Provider '" + name + "' is not configured.");
            return false;
        }
        if (ProviderRegistry.builtIns().containsKey(name)) {
            Output.error("Provider '" + name + "' is built-in and cannot be removed.");
            return false;
        }

        List<AppManifest> manifests;
        try {
            manifests = store.stateStore().scanManifests().stream()
                .filter(manifest -> name.equals(manifest.provider()))
                .toList();
        } catch (IOException e) {
            Output.error("Failed to read installed packages: " + e.getMessage());
            return false;
        }

        if (!manifests.isEmpty()) {
            Output.info("Provider " + name + " (" + config.type() + ", "
                + config.resolvedApi() + ") has " + manifests.size()
                + " installed package(s):");
            for (AppManifest manifest : manifests) {
                Output.info("  " + name + ":" + manifest.repo() + " "
                    + (manifest.version() == null ? "unknown" : manifest.version()));
            }
            if (!options.flags().contains("purge") && !options.flags().contains("abandon")) {
                Output.error("Refusing to remove the provider while packages are installed.");
                Output.error("  --purge     uninstall them, delete their state, then remove it");
                Output.error("  --abandon   remove only the provider config; packages remain");
                return false;
            }
            if (options.flags().contains("purge")) {
                if (!confirm.confirm("Uninstall " + manifests.size()
                    + " package(s) and remove provider " + name + " ("
                    + config.type() + ", " + config.resolvedApi() + ")?")) {
                    Output.error("Provider removal declined.");
                    return false;
                }
                for (AppManifest manifest : manifests) {
                    if (!uninstallService.remove(manifest)) {
                        Output.error("Stopped; provider " + name + " was not removed.");
                        return false;
                    }
                }
                try {
                    store.stateStore().rebuildIndex();
                } catch (IOException e) {
                    Output.error("Packages uninstalled, but the index could not be rebuilt: "
                        + e.getMessage());
                    return false;
                }
            } else {
                if (!confirm.confirm("Remove provider " + name + " and leave "
                    + manifests.size() + " package(s) orphaned?")) {
                    Output.error("Provider removal declined.");
                    return false;
                }
            }
        }

        Map<String, ProviderConfig> map = store.currentProviders();
        map.remove(name);
        if (!store.saveProviders(map)) {
            return false;
        }
        if (store.cmdParser().isJson()) {
            Output.json(new RemovedProvider(name));
        } else {
            Output.info("Removed provider " + name + ".");
        }
        return true;
    }
}
