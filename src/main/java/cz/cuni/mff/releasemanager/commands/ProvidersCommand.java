package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.Confirmer;
import cz.cuni.mff.releasemanager.install.UninstallService;
import cz.cuni.mff.releasemanager.json.JsonViews.PackageView;
import cz.cuni.mff.releasemanager.json.JsonViews.ProviderConfigView;
import cz.cuni.mff.releasemanager.json.JsonViews.ProviderDetail;
import cz.cuni.mff.releasemanager.json.JsonViews.ProviderEntry;
import cz.cuni.mff.releasemanager.json.JsonViews.ProviderList;
import cz.cuni.mff.releasemanager.json.JsonViews.ProviderResult;
import cz.cuni.mff.releasemanager.json.JsonViews.RemovedProvider;
import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.ProviderDetector;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.provider.ProviderType;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.Output;

// provider list, show, add, update, remove, and recover
public final class ProvidersCommand {

    private final CmdParser cmdParser;
    private final StateStore stateStore;
    private final Confirmer confirm;
    private final UninstallService uninstallService;
    private GrmConfig toolConfig;
    private ProviderRegistry providers;

    public ProvidersCommand(
        CmdParser cmdParser,
        StateStore stateStore,
        GrmConfig toolConfig,
        ProviderRegistry providers,
        Confirmer confirm,
        UninstallService uninstallService) {
        this.cmdParser = cmdParser;
        this.stateStore = stateStore;
        this.toolConfig = toolConfig;
        this.providers = providers;
        this.confirm = confirm;
        this.uninstallService = uninstallService;
    }

    public boolean run(Command command) {
        List<String> args = command.arguments == null ? List.of() : command.arguments;
        if (args.isEmpty()) {
            return providersList();
        }
        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "list" -> args.size() == 1 ? providersList() : providersUsage();
            case "show" -> args.size() == 2 ? providersShow(args.get(1)) : providersUsage();
            case "add" -> providersAdd(args.subList(1, args.size()));
            case "update" -> providersUpdate(args.subList(1, args.size()));
            case "remove" -> providersRemove(args.subList(1, args.size()));
            case "recover" -> providersRecover(args.subList(1, args.size()));
            default -> providersUsage();
        };
    }

    private boolean providersUsage() {
        Output.error("Usage:");
        Output.error("  providers list");
        Output.error("  providers show <name>");
        Output.error("  providers add <name> <url> [--type auto|github|gitea|gitlab]"
            + " [--api URL] [--token-env VAR]");
        Output.error("  providers update <name> [--type ...] [--api URL] [--url URL]"
            + " [--token-env VAR] [--force]");
        Output.error("  providers remove <name> [--abandon | --purge]");
        Output.error("  providers recover <name> [--type ...] [--api URL]");
        return false;
    }

    private record ProviderOptions(
        Map<String, String> values,
        List<String> positional,
        Set<String> flags
    ) {}

    private ProviderOptions parseProviderOptions(
        List<String> args, Set<String> flagNames, Set<String> valueNames) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        Set<String> flags = new HashSet<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("--")) {
                positional.add(arg);
                continue;
            }
            String name = arg.substring(2);
            if (flagNames.contains(name)) {
                flags.add(name);
            } else if (valueNames.contains(name)) {
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException("Missing value for --" + name);
                }
                values.put(name, args.get(++i));
            } else {
                throw new IllegalArgumentException("Unknown option: --" + name);
            }
        }
        return new ProviderOptions(values, positional, flags);
    }

    private ProviderConfigView providerEntry(ProviderConfig config) {
        return new ProviderConfigView(
            config.name(),
            config.type(),
            config.url(),
            config.resolvedApi(),
            config.tokenEnv(),
            ProviderRegistry.builtIns().containsKey(config.name()));
    }

    private boolean providersList() {
        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            Output.error("Failed to read installed packages: " + e.getMessage());
            return false;
        }
        List<ProviderConfig> configs = new ArrayList<>(providers.all());
        configs.sort(java.util.Comparator.comparing(ProviderConfig::name));
        List<ProviderEntry> entries = new ArrayList<>();
        for (ProviderConfig config : configs) {
            long count = manifests.stream()
                .filter(manifest -> config.name().equals(manifest.provider()))
                .count();
            boolean builtIn = ProviderRegistry.builtIns().containsKey(config.name());
            String tokenState = null;
            if (config.tokenEnv() != null && !config.tokenEnv().isBlank()) {
                tokenState = System.getenv(config.tokenEnv()) == null ? "missing" : "set";
            }
            if (cmdParser.isJson()) {
                entries.add(new ProviderEntry(
                    config.name(),
                    config.type(),
                    config.url(),
                    config.resolvedApi(),
                    config.tokenEnv(),
                    tokenState,
                    builtIn,
                    count));
                continue;
            }
            String token = config.tokenEnv() == null || config.tokenEnv().isBlank()
                ? ""
                : " token-env=" + config.tokenEnv() + " (" + tokenState + ")";
            Output.data(config.name() + " (" + config.type() + ", "
                + config.resolvedApi() + ") packages=" + count
                + (builtIn ? " [built-in]" : "") + token);
        }
        if (cmdParser.isJson()) {
            Output.json(new ProviderList(entries));
        }
        return true;
    }

    private boolean providersShow(String name) {
        ProviderConfig config = providers.get(name).orElse(null);
        if (config == null) {
            Output.error("Provider '" + name + "' is not configured.");
            return false;
        }
        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests().stream()
                .filter(manifest -> config.name().equals(manifest.provider()))
                .toList();
        } catch (IOException e) {
            Output.error("Failed to read installed packages: " + e.getMessage());
            return false;
        }
        boolean builtIn = ProviderRegistry.builtIns().containsKey(config.name());
        if (cmdParser.isJson()) {
            List<PackageView> packages = new ArrayList<>();
            for (AppManifest manifest : manifests) {
                packages.add(new PackageView(
                    manifest.repo(),
                    manifest.version() == null ? "" : manifest.version()));
            }
            Output.json(new ProviderDetail(
                config.name(),
                config.type(),
                config.url(),
                config.resolvedApi(),
                config.tokenEnv(),
                builtIn,
                packages));
            return true;
        }
        Output.data("Name: " + config.name());
        Output.data("Type: " + config.type());
        Output.data("URL: " + config.url());
        Output.data("API: " + config.resolvedApi());
        Output.data("Token env: "
            + (config.tokenEnv() == null || config.tokenEnv().isBlank()
                ? "none" : config.tokenEnv()));
        Output.data("Built-in: " + builtIn);
        Output.data("Packages: " + manifests.size());
        for (AppManifest manifest : manifests) {
            Output.data("  " + config.name() + ":" + manifest.repo() + " "
                + (manifest.version() == null ? "unknown" : manifest.version()));
        }
        return true;
    }

    private boolean providersAdd(List<String> args) {
        ProviderOptions options;
        try {
            options = parseProviderOptions(args, Set.of(),
                Set.of("type", "api", "token-env", "name"));
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return false;
        }
        if (options.positional().isEmpty() || options.positional().size() > 2) {
            return providersUsage();
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
        if (providers.get(name).isPresent()) {
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
                Output.info("Detected " + type.id() + " at " + urlBase
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
            Output.error("Invalid provider config; check the HTTPS URL, API base, and token variable.");
            return false;
        }
        Map<String, ProviderConfig> updated = currentProviders();
        updated.put(name, config);
        if (!saveProviders(updated)) {
            return false;
        }
        if (cmdParser.isJson()) {
            Output.json(new ProviderResult(providerEntry(config)));
        } else {
            Output.info("Added provider " + name + " (" + type.id() + ", "
                + config.resolvedApi() + ").");
        }
        return true;
    }

    private boolean providersUpdate(List<String> args) {
        ProviderOptions options;
        try {
            options = parseProviderOptions(args, Set.of("force"),
                Set.of("type", "api", "token-env", "url"));
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return false;
        }
        if (options.positional().size() != 1) {
            return providersUsage();
        }
        String name = options.positional().get(0);
        ProviderConfig existing = providers.get(name).orElse(null);
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
            Output.error("Invalid provider config; check the HTTPS URL, API base, and token variable.");
            return false;
        }

        try {
            List<AppManifest> manifests = stateStore.scanManifests().stream()
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
                if (!confirm.confirm("Change provider " + name + " from " + existing.resolvedApi()
                    + " to " + updatedConfig.resolvedApi()
                    + "? Installed packages will fetch updates from the new endpoint.")) {
                    Output.error("Provider update declined.");
                    return false;
                }
            }
        } catch (IOException e) {
            Output.error("Failed to read installed packages: " + e.getMessage());
            return false;
        }

        Map<String, ProviderConfig> map = currentProviders();
        map.put(name, updatedConfig);
        if (!saveProviders(map)) {
            return false;
        }
        if (cmdParser.isJson()) {
            Output.json(new ProviderResult(providerEntry(updatedConfig)));
        } else {
            Output.info("Updated provider " + name + ".");
        }
        return true;
    }

    private boolean providersRemove(List<String> args) {
        ProviderOptions options;
        try {
            options = parseProviderOptions(args, Set.of("abandon", "purge"), Set.of());
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return false;
        }
        if (options.positional().size() != 1) {
            return providersUsage();
        }
        if (options.flags().contains("abandon") && options.flags().contains("purge")) {
            Output.error("Use only one of --abandon or --purge.");
            return false;
        }
        String name = options.positional().get(0);
        ProviderConfig config = providers.get(name).orElse(null);
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
            manifests = stateStore.scanManifests().stream()
                .filter(manifest -> name.equals(manifest.provider()))
                .toList();
        } catch (IOException e) {
            Output.error("Failed to read installed packages: " + e.getMessage());
            return false;
        }

        if (!manifests.isEmpty()) {
            Output.info("Provider " + name + " (" + config.type() + ", "
                + config.resolvedApi() + ") has " + manifests.size() + " installed package(s):");
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
                if (!confirm.confirm("Uninstall " + manifests.size() + " package(s) and remove provider "
                    + name + " (" + config.type() + ", " + config.resolvedApi() + ")?")) {
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
                    stateStore.rebuildIndex();
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

        Map<String, ProviderConfig> map = currentProviders();
        map.remove(name);
        if (!saveProviders(map)) {
            return false;
        }
        if (cmdParser.isJson()) {
            Output.json(new RemovedProvider(name));
        } else {
            Output.info("Removed provider " + name + ".");
        }
        return true;
    }

    private boolean providersRecover(List<String> args) {
        ProviderOptions options;
        try {
            options = parseProviderOptions(args, Set.of(),
                Set.of("type", "api", "token-env"));
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return false;
        }
        if (options.positional().size() != 1) {
            return providersUsage();
        }
        String name = options.positional().get(0);
        if (providers.get(name).isPresent()) {
            Output.error("Provider '" + name + "' is already configured.");
            return false;
        }

        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests().stream()
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
        String url = deriveUrlFromApi(type, api);
        ProviderConfig config = new ProviderConfig(
            name, type.id(), url, api, options.values().get("token-env")).named(name);
        if (!config.isValid()) {
            Output.error("Recovered provider config is invalid.");
            return false;
        }

        if (!confirm.confirm("Recover provider " + name + " as " + type.id() + " (" + api
            + ") for " + manifests.size() + " installed package(s)?")) {
            Output.error("Provider recovery declined.");
            return false;
        }
        Map<String, ProviderConfig> map = currentProviders();
        map.put(name, config);
        if (!saveProviders(map)) {
            return false;
        }
        if (cmdParser.isJson()) {
            Output.json(new ProviderResult(providerEntry(config)));
        } else {
            Output.info("Recovered provider " + name + ".");
        }
        return true;
    }

    private Map<String, ProviderConfig> currentProviders() {
        return toolConfig.providers() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(toolConfig.providers());
    }

    private boolean saveProviders(Map<String, ProviderConfig> map) {
        GrmConfig updated = new GrmConfig(
            toolConfig.kind(),
            toolConfig.schemaVersion(),
            toolConfig.strictVerification(),
            toolConfig.backupCount(),
            toolConfig.installRoot(),
            toolConfig.requireSignature(),
            map,
            toolConfig.searchAllProviders());
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
        String name = normalized.replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[._-]+|[._-]+$", "");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Cannot derive a provider name from " + url);
        }
        return name;
    }

    private static String deriveUrlFromApi(ProviderType type, String api) {
        return switch (type) {
            case GITEA -> stripApiSuffix(api, "/api/v1");
            case GITLAB -> stripApiSuffix(api, "/api/v4");
            case GITHUB -> api.equals("https://api.github.com")
                ? "https://github.com"
                : stripApiSuffix(api, "/api/v3");
        };
    }

    private static String stripApiSuffix(String api, String suffix) {
        return api.endsWith(suffix)
            ? api.substring(0, api.length() - suffix.length())
            : api;
    }
}
