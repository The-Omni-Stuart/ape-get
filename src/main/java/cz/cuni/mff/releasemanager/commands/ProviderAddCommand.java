package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import cz.cuni.mff.releasemanager.json.JsonViews.ProviderResult;
import cz.cuni.mff.releasemanager.provider.ProviderDetector;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.types.ProviderType;
import cz.cuni.mff.releasemanager.utils.Output;

// providers add
final class ProviderAddCommand {
    private final ProviderStore store;

    ProviderAddCommand(ProviderStore store) {
        this.store = store;
    }

    boolean run(List<String> args) {
        ProviderOptionsParser.Options options;
        try {
            options = ProviderOptionsParser.parse(args, Set.of(),
                Set.of("type", "api", "token-env", "name"));
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return false;
        }
        if (options.positional().isEmpty() || options.positional().size() > 2) {
            return ProviderViews.usage();
        }

        String name;
        String url;
        if (options.positional().size() == 2) {
            name = options.positional().get(0);
            url = options.positional().get(1);
        } else {
            url = options.positional().get(0);
            name = options.values().get("name");
            if (name == null || name.isBlank()) {
                try {
                    name = deriveProviderName(url);
                } catch (IllegalArgumentException e) {
                    Output.error(e.getMessage());
                    return false;
                }
            }
        }
        if (!ProviderConfig.validName(name)) {
            Output.error("Invalid provider name: " + name);
            return false;
        }
        if (ProviderRegistry.builtIns().containsKey(name)) {
            Output.error("Provider '" + name + "' is built-in; choose another name.");
            return false;
        }
        if (store.providers().get(name).isPresent()) {
            Output.error("Provider '" + name + "' already exists; use providers update.");
            return false;
        }

        String urlBase = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String typeValue = options.values().getOrDefault("type", "auto");
        ProviderType type;
        String api = options.values().get("api");
        try {
            if ("auto".equalsIgnoreCase(typeValue)) {
                ProviderDetector.Detected detected = new ProviderDetector().detect(urlBase);
                type = detected.type();
                if (api == null || api.isBlank()) {
                    api = detected.api();
                }
                Output.info(type == ProviderType.RNGIT
                    ? "Detected rngit at " + urlBase + "."
                    : "Detected " + type.id() + " at " + urlBase
                        + " (API " + api + ").");
            } else {
                type = ProviderType.from(typeValue).orElse(null);
                if (type == null) {
                    Output.error("Unknown provider type: " + typeValue);
                    return false;
                }
            }
        } catch (IOException e) {
            Output.error(e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Output.error("Provider detection interrupted.");
            return false;
        }

        ProviderConfig config = new ProviderConfig(
            name, type.id(), urlBase, api, options.values().get("token-env")).named(name);
        if (!config.isValid()) {
            Output.error("Invalid provider config; check the URL (https:// or rns://),"
                + " API base, and token variable.");
            return false;
        }
        Map<String, ProviderConfig> updated = store.currentProviders();
        updated.put(name, config);
        if (!store.saveProviders(updated)) {
            return false;
        }
        if (store.cmdParser().isJson()) {
            Output.json(new ProviderResult(ProviderViews.entry(config)));
        } else {
            Output.info("Added provider " + name + " (" + type.id() + ", "
                + config.resolvedApi() + ").");
        }
        return true;
    }

    private static String deriveProviderName(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("Invalid provider URL: " + url);
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("github.com")) {
            return "github";
        }
        if (normalized.equals("codeberg.org")) {
            return "codeberg";
        }
        if (normalized.equals("gitlab.com")) {
            return "gitlab";
        }
        if ("rns".equalsIgnoreCase(uri.getScheme())) {
            String alias = normalized.replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
            boolean hash = alias.matches("[0-9a-f]{32,64}");
            return "rns-" + (hash ? alias.substring(0, 8) : alias);
        }
        String name = normalized.replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[._-]+|[._-]+$", "");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Cannot derive a provider name from " + url);
        }
        return name;
    }
}
