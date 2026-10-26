package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.Confirmer;
import cz.cuni.mff.releasemanager.install.UninstallService;
import cz.cuni.mff.releasemanager.json.JsonViews.PackageView;
import cz.cuni.mff.releasemanager.json.JsonViews.ProviderDetail;
import cz.cuni.mff.releasemanager.json.JsonViews.ProviderEntry;
import cz.cuni.mff.releasemanager.json.JsonViews.ProviderList;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.Output;

// provider list, show, add, update, remove, and recover
public final class ProvidersCommand {

    private final CmdParser cmdParser;
    private final StateStore stateStore;
    private final Confirmer confirm;
    private final UninstallService uninstallService;
    private final ProviderStore store;

    public ProvidersCommand(
        CmdParser cmdParser,
        StateStore stateStore,
        GrmConfig toolConfig,
        ProviderRegistry providers,
        Confirmer confirm,
        UninstallService uninstallService) {
        this.cmdParser = cmdParser;
        this.stateStore = stateStore;
        this.confirm = confirm;
        this.uninstallService = uninstallService;
        this.store = new ProviderStore(cmdParser, stateStore, toolConfig, providers);
    }

    public boolean run(Command command) {
        List<String> args = command.arguments == null ? List.of() : command.arguments;
        if (args.isEmpty()) {
            return providersList();
        }
        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = args.subList(1, args.size());
        return switch (subcommand) {
            case "list" -> args.size() == 1 ? providersList() : ProviderViews.usage();
            case "show" -> args.size() == 2
                ? providersShow(args.get(1)) : ProviderViews.usage();
            case "add" -> new ProviderAddCommand(store).run(rest);
            case "update" -> new ProviderUpdateCommand(store, confirm).run(rest);
            case "remove" -> new ProviderRemoveCommand(
                store, confirm, uninstallService).run(rest);
            case "recover" -> new ProviderRecoverCommand(store, confirm).run(rest);
            default -> ProviderViews.usage();
        };
    }

    private boolean providersList() {
        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            Output.error("Failed to read installed packages: " + e.getMessage());
            return false;
        }
        List<ProviderConfig> configs = new ArrayList<>(store.providers().all());
        configs.sort(Comparator.comparing(ProviderConfig::name));
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
        ProviderConfig config = store.providers().get(name).orElse(null);
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
}
