package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cz.cuni.mff.releasemanager.cmd.Confirmer;
import cz.cuni.mff.releasemanager.json.JsonViews.ProviderResult;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.types.ProviderType;
import cz.cuni.mff.releasemanager.utils.Output;

// providers update
final class ProviderUpdateCommand {
    private final ProviderStore store;
    private final Confirmer confirm;

    ProviderUpdateCommand(ProviderStore store, Confirmer confirm) {
        this.store = store;
        this.confirm = confirm;
    }

    boolean run(List<String> args) {
        ProviderOptionsParser.Options options;
        try {
            options = ProviderOptionsParser.parse(args, Set.of("force"),
                Set.of("type", "api", "token-env", "url"));
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return false;
        }
        if (options.positional().size() != 1) {
            return ProviderViews.usage();
        }
        String name = options.positional().get(0);
        ProviderConfig existing = store.providers().get(name).orElse(null);
        if (existing == null) {
            Output.error("Provider '" + name + "' is not configured.");
            return false;
        }
        if (ProviderRegistry.builtIns().containsKey(name)) {
            Output.error("Provider '" + name + "' is built-in and cannot be updated.");
            return false;
        }

        String typeValue = options.values().getOrDefault("type", existing.type());
        ProviderType type = ProviderType.from(typeValue).orElse(null);
        if (type == null) {
            Output.error("Unknown provider type: " + typeValue);
            return false;
        }
        String url = options.values().getOrDefault("url", existing.url());
        String api = options.values().get("api");
        if (api == null && type.id().equals(existing.type())
            && url.equals(existing.url())) {
            api = existing.api(); // unchanged endpoint keeps its custom API
        }
        String tokenEnv = options.values().containsKey("token-env")
            ? options.values().get("token-env") : existing.tokenEnv();
        ProviderConfig updatedConfig = new ProviderConfig(
            name, type.id(), url, api, tokenEnv).named(name);
        if (!updatedConfig.isValid()) {
            Output.error("Invalid provider config; check the URL (https:// or rns://),"
                + " API base, and token variable.");
            return false;
        }

        try {
            List<AppManifest> manifests = store.stateStore().scanManifests().stream()
                .filter(manifest -> name.equals(manifest.provider()))
                .toList();
            boolean endpointChanged = !type.id().equals(existing.type())
                || !updatedConfig.resolvedApi().equals(existing.resolvedApi());
            if (endpointChanged && !manifests.isEmpty()) {
                if (!options.flags().contains("force")) {
                    Output.error("Provider '" + name + "' has " + manifests.size()
                        + " installed package(s); changing type/API requires --force.");
                    return false;
                }
                if (!confirm.confirm("Change provider " + name + " from "
                    + existing.resolvedApi() + " to " + updatedConfig.resolvedApi()
                    + "? Installed packages will fetch updates from the new endpoint.")) {
                    Output.error("Provider update declined.");
                    return false;
                }
            }
        } catch (IOException e) {
            Output.error("Failed to read installed packages: " + e.getMessage());
            return false;
        }

        Map<String, ProviderConfig> map = store.currentProviders();
        map.put(name, updatedConfig);
        if (!store.saveProviders(map)) {
            return false;
        }
        if (store.cmdParser().isJson()) {
            Output.json(new ProviderResult(ProviderViews.entry(updatedConfig)));
        } else {
            Output.info("Updated provider " + name + ".");
        }
        return true;
    }
}
