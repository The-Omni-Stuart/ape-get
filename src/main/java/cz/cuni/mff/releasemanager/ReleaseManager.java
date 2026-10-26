package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.Confirmation;
import cz.cuni.mff.releasemanager.cmd.Confirmer;
import cz.cuni.mff.releasemanager.cmd.ConsoleConfirmer;
import cz.cuni.mff.releasemanager.cmd.ConsolePrompt;
import cz.cuni.mff.releasemanager.cmd.Prompt;
import cz.cuni.mff.releasemanager.commands.AdoptCommand;
import cz.cuni.mff.releasemanager.commands.Doctor;
import cz.cuni.mff.releasemanager.commands.InfoCommand;
import cz.cuni.mff.releasemanager.commands.ListCommand;
import cz.cuni.mff.releasemanager.commands.OutdatedCommand;
import cz.cuni.mff.releasemanager.commands.ProvidersCommand;
import cz.cuni.mff.releasemanager.commands.RollbackService;
import cz.cuni.mff.releasemanager.commands.SearchCommand;
import cz.cuni.mff.releasemanager.commands.UninstallCommand;
import cz.cuni.mff.releasemanager.commands.Usage;
import cz.cuni.mff.releasemanager.install.AssetChooser;
import cz.cuni.mff.releasemanager.install.AutoUpdater;
import cz.cuni.mff.releasemanager.install.BackupService;
import cz.cuni.mff.releasemanager.install.InstallComponents;
import cz.cuni.mff.releasemanager.install.InstallReporter;
import cz.cuni.mff.releasemanager.install.InstallService;
import cz.cuni.mff.releasemanager.install.ManifestFactory;
import cz.cuni.mff.releasemanager.install.PackagePlanner;
import cz.cuni.mff.releasemanager.install.PlatformInstaller;
import cz.cuni.mff.releasemanager.install.ReleaseDownloader;
import cz.cuni.mff.releasemanager.install.ReleaseFetcher;
import cz.cuni.mff.releasemanager.install.TrustGateFactory;
import cz.cuni.mff.releasemanager.install.UninstallService;
import cz.cuni.mff.releasemanager.install.UpdateChecker;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.platform.PlatformHandlers;
import cz.cuni.mff.releasemanager.platform.PlatformPaths;
import cz.cuni.mff.releasemanager.provider.InstallSourceResolver;
import cz.cuni.mff.releasemanager.provider.NetworkSettings;
import cz.cuni.mff.releasemanager.provider.ProviderFactory;
import cz.cuni.mff.releasemanager.provider.ProviderProbe;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.provider.SearchService;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.ManifestStore;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.state.StateLock;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.state.UpdateLog;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.verify.AssetVerifier;
import cz.cuni.mff.releasemanager.verify.SignatureVerifier;
import cz.cuni.mff.releasemanager.verify.SignatureVerifiers;

// command dispatch and the short-lived wiring each command needs
public class ReleaseManager {

    private static ReleaseManager instance;

    private final CmdParser cmdParser;
    private final ProviderFactory providerFactory;
    private final PlatformHandler platformHandler;
    private final AssetVerifier verifier;
    private final SignatureVerifier signatureVerifier;
    private final StateStore stateStore;
    private final ManifestStore manifestStore;
    private final BackupManager backupManager;
    private final Confirmer confirmer;
    private Platform.OS os = Platform.detectPlatform().getOS();
    private GrmConfig toolConfig = GrmConfig.defaults();
    private ProviderRegistry providers = new ProviderRegistry();
    private SearchService searchService = new SearchService();
    private ProviderProbe providerProbe = new ProviderProbe();

    private ReleaseManager() {
        cmdParser = new CmdParser();
        providerFactory = new ProviderFactory();
        platformHandler = PlatformHandlers.current();
        verifier = new AssetVerifier();
        signatureVerifier = SignatureVerifiers.forCurrentPlatform();
        stateStore = new StateStore(PlatformPaths.current());
        manifestStore = new ManifestStore(stateStore);
        backupManager = new BackupManager(stateStore.paths());
        Prompt prompt = new ConsolePrompt(cmdParser);
        confirmer = new ConsoleConfirmer(prompt, cmdParser);
    }

    // package-private seam so tests can supply their own collaborators
    ReleaseManager(
        CmdParser cmdParser,
        ProviderFactory providerFactory,
        PlatformHandler platformHandler,
        AssetVerifier verifier,
        SignatureVerifier signatureVerifier,
        StateStore stateStore,
        BackupManager backupManager,
        Prompt prompt,
        SearchService searchService,
        Platform.OS os) {
        this.cmdParser = cmdParser;
        this.providerFactory = providerFactory;
        this.platformHandler = platformHandler;
        this.verifier = verifier;
        this.signatureVerifier = signatureVerifier;
        this.stateStore = stateStore;
        this.manifestStore = new ManifestStore(stateStore);
        this.backupManager = backupManager;
        this.confirmer = new ConsoleConfirmer(prompt, cmdParser);
        this.searchService = searchService;
        this.os = os;
    }

    // lets a test exercise another platform without a real machine
    void setOs(Platform.OS os) {
        this.os = os;
    }

    void setProviderProbe(ProviderProbe providerProbe) {
        this.providerProbe = providerProbe;
    }

    // singleton used by Main
    public static ReleaseManager getInstance() {
        if (instance == null) {
            instance = new ReleaseManager();
        }
        return instance;
    }

    // parse, lock stateful commands, repair state, then dispatch
    public int execute(String[] args) {
        Command command = cmdParser.parse(args);
        Output.configure(cmdParser.isQuiet(), cmdParser.isJson());

        boolean stateful = command == Command.INSTALL
            || command == Command.UPDATE
            || command == Command.UNINSTALL
            || command == Command.LIST
            || command == Command.PROVIDERS
            || command == Command.ROLLBACK
            || command == Command.ADOPT;
        if (!stateful) {
            return runCommand(command);
        }

        try (StateLock ignored = StateLock.acquire(stateStore.paths().lockFile())) {
            if (!ensureState()) {
                return 1;
            }
            if (command == Command.LIST && !cmdParser.isOutdated()
                && !cmdParser.isDryRun()) {
                new AutoUpdater(
                    toolConfig, stateStore, cmdParser, updateChecker(),
                    installService(), new UpdateLog(stateStore.paths().updateLogFile()))
                    .maybeRun();
            }
            return runCommand(command);
        } catch (IOException e) {
            Output.error("Cannot acquire state lock at "
                + stateStore.paths().lockFile() + ": " + e.getMessage());
            return 1;
        }
    }

    private int runCommand(Command command) {
        return switch (command) {
            case SEARCH -> search(command) ? 0 : 1;
            case INSTALL -> install(command) ? 0 : 1;
            case UNINSTALL -> new UninstallCommand(
                cmdParser, stateStore, uninstallService(),
                new Confirmation(confirmer, cmdParser)).run(command) ? 0 : 1;
            case UPDATE -> update(command) ? 0 : 1;
            case LIST -> list() ? 0 : 1;
            case INFO -> new InfoCommand(
                cmdParser, stateStore, platformHandler).run(command) ? 0 : 1;
            case PROVIDERS -> providers(command) ? 0 : 1;
            case ROLLBACK -> rollback(command) ? 0 : 1;
            case DOCTOR -> doctor() ? 0 : 1;
            case ADOPT -> new AdoptCommand(
                os, cmdParser, stateStore, manifestStore, platformHandler,
                providers, confirmer).run(command) ? 0 : 1;
            case HELP -> {
                help();
                yield 0;
            }
            case USAGE -> usage(command);
        };
    }

    private boolean ensureState() {
        GrmConfig loaded = new StateBootstrap(stateStore, confirmer).prepare();
        if (loaded == null) {
            return false;
        }
        toolConfig = loaded;
        providers = new ProviderRegistry(toolConfig.providers());
        if (!applyNetworkSettings()) {
            return false;
        }
        applyInstallRoot();
        return true;
    }

    private boolean applyNetworkSettings() {
        try {
            NetworkSettings.configure(
                toolConfig.networkTimeoutSeconds(), toolConfig.proxy());
            return true;
        } catch (IllegalArgumentException e) {
            Output.error("Invalid network config: " + e.getMessage());
            return false;
        }
    }

    private ReleaseProvider resolveProvider(String name) {
        ProviderConfig config = providers.get(name).orElse(null);
        if (config == null) {
            Output.error("Provider '" + name + "' is not configured.");
            return null;
        }
        var type = config.providerType().orElse(null);
        if (type == null) {
            Output.error("Provider '" + name + "' has an unsupported type.");
            return null;
        }
        return providerFactory.create(config);
    }

    // search across the configured providers
    private boolean search(Command command) {
        if (!loadSearchConfig()) {
            return false;
        }
        return new SearchCommand(
            cmdParser, searchService, toolConfig, providers,
            this::resolveProvider).run(command);
    }

    private boolean loadSearchConfig() {
        try {
            toolConfig = stateStore.loadConfig().config();
            providers = new ProviderRegistry(toolConfig.providers());
            return applyNetworkSettings();
        } catch (IOException e) {
            Output.error("Failed to read config: " + e.getMessage());
            return false;
        }
    }

    private boolean install(Command command) {
        String argument = command.argument;
        if (argument != null && !argument.isBlank()) {
            String resolved = new InstallSourceResolver(
                cmdParser, providers, this::resolveProvider,
                searchService, providerProbe).resolve(argument);
            if (resolved == null) {
                return false;
            }
            command.argument = resolved;
        }
        return installService().install(command);
    }

    private InstallService installService() {
        return new InstallService(
            cmdParser,
            manifestStore,
            this::resolveProvider,
            new InstallComponents(
                new ReleaseFetcher(),
                new AssetChooser(cmdParser),
                new ReleaseDownloader(),
                new ManifestFactory(platformHandler, stateStore.paths()),
                new InstallReporter(cmdParser, platformHandler),
                new TrustGateFactory(
                    cmdParser, toolConfig, verifier, signatureVerifier, confirmer),
                new BackupService(os, toolConfig, backupManager, platformHandler),
                new PlatformInstaller(platformHandler),
                new Confirmation(confirmer, cmdParser)),
            new PackagePlanner(os, cmdParser),
            toolConfig.channel());
    }

    // built per call so it follows the live state store and platform
    private UninstallService uninstallService() {
        return new UninstallService(stateStore, platformHandler);
    }

    private boolean list() {
        if (cmdParser.isOutdated()) {
            return new OutdatedCommand(
                cmdParser, stateStore, updateChecker(), toolConfig.channel()).run();
        }
        return new ListCommand(cmdParser, stateStore, platformHandler).run();
    }

    private UpdateChecker updateChecker() {
        return new UpdateChecker(this::resolveProvider, new ReleaseFetcher(), platformHandler);
    }

    private boolean providers(Command command) {
        return new ProvidersCommand(
            cmdParser, stateStore, toolConfig, providers,
            confirmer, uninstallService()).run(command);
    }

    private boolean update(Command command) {
        if (cmdParser.isAll()) {
            return installService().updateAll(true, true);
        }
        return installService().update(command);
    }

    private boolean rollback(Command command) {
        return new RollbackService(
            os, cmdParser, manifestStore, backupManager, platformHandler,
            confirmer).rollback(command);
    }

    private void applyInstallRoot() {
        String root = toolConfig.installRoot();
        if (root == null || root.isBlank()) {
            return;
        }
        try {
            platformHandler.setInstallRoot(Path.of(root));
        } catch (InvalidPathException e) {
            Output.error("Ignoring invalid install_root: " + root);
        }
    }

    private boolean doctor() {
        return new Doctor(
            os, cmdParser, stateStore, platformHandler, signatureVerifier).run();
    }

    // usage on stdout
    private void help() {
        Usage.help();
    }

    // unknown command: message plus usage on stderr
    private int usage(Command command) {
        return Usage.unknown(command);
    }
}
