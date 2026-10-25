package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
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
import cz.cuni.mff.releasemanager.commands.UninstallCommand;
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
import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.ProviderFactory;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.provider.SearchCommand;
import cz.cuni.mff.releasemanager.provider.SearchService;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.ManifestStore;
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

    private ReleaseManager() {
        cmdParser = new CmdParser();
        providerFactory = new ProviderFactory();
        platformHandler = Platform.getPlatformHandler();
        verifier = new AssetVerifier();
        signatureVerifier = SignatureVerifiers.forCurrentPlatform();
        stateStore = new StateStore(AppPaths.forCurrentPlatform());
        manifestStore = new ManifestStore(stateStore);
        backupManager = new BackupManager(stateStore.paths());
        Prompt prompt = new ConsolePrompt();
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
                cmdParser, stateStore, uninstallService()).run(command) ? 0 : 1;
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
        applyInstallRoot();
        return true;
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
            return true;
        } catch (IOException e) {
            Output.error("Failed to read config: " + e.getMessage());
            return false;
        }
    }

    private boolean install(Command command) {
        return installService().install(command);
    }

    // built per call so it sees the current config and platform
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
                new PlatformInstaller(platformHandler)),
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
        return new UpdateChecker(this::resolveProvider, new ReleaseFetcher());
    }

    private boolean providers(Command command) {
        return new ProvidersCommand(
            cmdParser, stateStore, toolConfig, providers,
            confirmer, uninstallService()).run(command);
    }

    private boolean update(Command command) {
        if (cmdParser.isAll()) {
            return installService().updateAll(true);
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
        printUsage(System.out);
    }

    // unknown command: message plus usage on stderr
    private int usage(Command command) {
        if (command.argument != null && !command.argument.isBlank()) {
            Output.error("Unknown command: " + command.argument);
        }
        printUsage(System.err);
        return 2;
    }

    private void printUsage(PrintStream out) {
        out.println("Usage: java -jar github-releases-manager.jar [command] [options]");
        out.println("Commands:");
        out.println("  search [name] - search for a release by name");
        out.println("  install [name] - install the latest release by name");
        out.println("  uninstall [name] - uninstall the release by name");
        out.println("  update [name] - update one release, or use --all");
        out.println("  list - list all installed releases");
        out.println("  info [name] - show details for an installed release");
        out.println("  providers - list, add, update, remove, or recover provider instances");
        out.println("  rollback [name] - restore the latest backup");
        out.println("  doctor - diagnose state and orphaned installs");
        out.println("  adopt [name] - adopt an existing Linux install");
        out.println("  help - display this help message");
        out.println("Options:");
        out.println("  --strict - fail when a checksum is missing or unusable");
        out.println("  --require-signature - fail when no verified signature is available");
        out.println("  --allow-signer-change - accept a changed signer after confirmation");
        out.println("  --provider <name> - use a configured provider for search");
        out.println("  --asset <name|index> - choose a release asset non-interactively");
        out.println("  --kind <kind> - force appimage, portable-exe, ape, nsis, msi, or dmg");
        out.println("  --name <command> - command name for a portable package");
        out.println("  --no-command - install without creating a command");
        out.println("  --desktop - create a desktop entry (Linux)");
        out.println("  --terminal - run the desktop entry in a terminal");
        out.println("  --icon <path> - icon for the desktop entry");
        out.println("  --assimilate - convert an APE with the cosmocc assimilate tool");
        out.println("  --version <tag> - install or update to a specific release");
        out.println("  --all - update every installed package");
        out.println("  --outdated - list only packages with newer releases");
        out.println("  --channel <stable|beta> - choose the release channel");
        out.println("  --dry-run - show what would happen without changing anything");
        out.println("  --json - machine-readable output");
        out.println("  --quiet - suppress progress messages");
        out.println("  --yes - assume yes for confirmation prompts");
    }
}
