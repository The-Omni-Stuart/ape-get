package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cz.cuni.mff.releasemanager.cmd.Confirmer;
import cz.cuni.mff.releasemanager.json.JsonViews.ProviderResult;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.types.ProviderType;
import cz.cuni.mff.releasemanager.utils.Output;

// providers recover
final class ProviderRecoverCommand {
    private final ProviderStore store;
    private final Confirmer confirm;

    ProviderRecoverCommand(ProviderStore store, Confirmer confirm) {
        this.store = store;
        this.confirm = confirm;
    }

    boolean run(List<String> args) {
        ProviderOptionsParser.Options options;
        try {
            options = ProviderOptionsParser.parse(args, Set.of(),
                Set.of("type", "api", "token-env"));
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return false;
        }
        if (options.positional().size() != 1) {
            return ProviderViews.usage();
        }
        String name = options.positional().get(0);
        if (store.providers().get(name).isPresent()) {
            Output.error("Provider '" + name + "' is already configured.");
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
        if (manifests.isEmpty()) {
            Output.error("No installed packages reference provider '" + name + "'.");
            return false;
        }

        Set<String> types = new HashSet<>();
        Set<String> apis = new HashSet<>();
        for (AppManifest manifest : manifests) {
            if (manifest.providerType() != null && !manifest.providerType().isBlank()) {
                types.add(manifest.providerType());
            }
            if (manifest.providerApi() != null && !manifest.providerApi().isBlank()) {
                apis.add(manifest.providerApi());
            }
        }
        if (types.isEmpty() || apis.isEmpty()) {
            Output.error("Installed manifests do not record the provider type and API.");
            Output.error("Pass --type and --api to recover this provider.");
            return false;
        }
        if (types.size() > 1 || apis.size() > 1) {
            Output.error("Installed manifests disagree about this provider:");
            Output.error("  types: " + types);
            Output.error("  APIs:  " + apis);
            return false;
        }

        String typeValue = options.values().getOrDefault("type", types.iterator().next());
        ProviderType type = ProviderType.from(typeValue).orElse(null);
        if (type == null) {
            Output.error("Unknown provider type: " + typeValue);
            return false;
        }
        String api = options.values().getOrDefault("api", apis.iterator().next());
        String url = type.urlFromApi(api);
        ProviderConfig config = new ProviderConfig(
            name, type.id(), url, api, options.values().get("token-env")).named(name);
        if (!config.isValid()) {
            Output.error("Recovered provider config is invalid.");
            return false;
        }

        if (!confirm.confirm("Recover provider " + name + " as " + type.id() + " ("
            + api + ") for " + manifests.size() + " installed package(s)?")) {
            Output.error("Provider recovery declined.");
            return false;
        }
        Map<String, ProviderConfig> map = store.currentProviders();
        map.put(name, config);
        if (!store.saveProviders(map)) {
            return false;
        }
        if (store.cmdParser().isJson()) {
            Output.json(new ProviderResult(ProviderViews.entry(config)));
        } else {
            Output.info("Recovered provider " + name + ".");
        }
        return true;
    }
}
