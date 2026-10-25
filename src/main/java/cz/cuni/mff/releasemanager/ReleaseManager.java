package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.ConsolePrompt;
import cz.cuni.mff.releasemanager.cmd.Prompt;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.GithubProvider;
import cz.cuni.mff.releasemanager.provider.GitLabProvider;
import cz.cuni.mff.releasemanager.provider.GiteaProvider;
import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.provider.ProvidersCommand;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.provider.SearchCommand;
import cz.cuni.mff.releasemanager.provider.SearchService;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.StateLock;
import cz.cuni.mff.releasemanager.state.ManifestViews;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.FileUtils;
import cz.cuni.mff.releasemanager.utils.Json;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.verify.AssetVerifier;
import cz.cuni.mff.releasemanager.verify.SignatureVerifier;
import cz.cuni.mff.releasemanager.verify.SignatureVerifiers;

// dispatch, and the commands that do not need their own class
public class ReleaseManager {

    private static ReleaseManager instance;

    private final CmdParser cmdParser;
    private final GithubClient githubClient;
    private final PlatformHandler platformHandler;
    private final AssetVerifier verifier;
    private final SignatureVerifier signatureVerifier;
    private final StateStore stateStore;
    private final BackupManager backupManager;
    private final Prompt prompt;
    private Platform.OS os = Platform.detectPlatform().getOS();
    private GrmConfig toolConfig = GrmConfig.defaults();
    private ProviderRegistry providers = new ProviderRegistry();
    private SearchService searchService = new SearchService();

    private ReleaseManager() {
        cmdParser = new CmdParser();
        githubClient = new GithubClient();
        platformHandler = Platform.getPlatformHandler();
        verifier = new AssetVerifier();
        signatureVerifier = SignatureVerifiers.forCurrentPlatform();
        stateStore = new StateStore(AppPaths.forCurrentPlatform());
        backupManager = new BackupManager(stateStore.paths());
        prompt = new ConsolePrompt();
    }

    // package-private seam so tests can supply their own collaborators
    ReleaseManager(
        CmdParser cmdParser,
        GithubClient githubClient,
        PlatformHandler platformHandler,
        AssetVerifier verifier,
        SignatureVerifier signatureVerifier,
        StateStore stateStore,
        BackupManager backupManager,
        Prompt prompt,
        SearchService searchService,
        Platform.OS os) {
        this.cmdParser = cmdParser;
        this.githubClient = githubClient;
        this.platformHandler = platformHandler;
        this.verifier = verifier;
        this.signatureVerifier = signatureVerifier;
        this.stateStore = stateStore;
        this.backupManager = backupManager;
        this.prompt = prompt;
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
            return runCommand(command);
        } catch (IOException e) {
            System.err.println("Cannot acquire state lock at "
                + stateStore.paths().lockFile() + ": " + e.getMessage());
            return 1;
        }
    }

    private int runCommand(Command command) {
        return switch (command) {
            case SEARCH -> search(command) ? 0 : 1;
            case INSTALL -> install(command) ? 0 : 1;
            case UNINSTALL -> uninstall(command) ? 0 : 1;
            case UPDATE -> update(command) ? 0 : 1;
            case LIST -> list() ? 0 : 1;
            case INFO -> info(command) ? 0 : 1;
            case PROVIDERS -> providers(command) ? 0 : 1;
            case ROLLBACK -> rollback(command) ? 0 : 1;
            case DOCTOR -> doctor() ? 0 : 1;
            case ADOPT -> adopt(command) ? 0 : 1;
            case HELP -> {
                help();
                yield 0;
            }
            case USAGE -> usage(command);
        };
    }

    private boolean ensureState() {
        GrmConfig loaded = new StateBootstrap(stateStore, this::confirm).prepare();
        if (loaded == null) {
            return false;
        }
        toolConfig = loaded;
        providers = new ProviderRegistry(toolConfig.providers());
        applyInstallRoot();
        return true;
    }

    private void printJson(Object value) {
        try {
            Output.data(Json.write(value));
        } catch (JsonProcessingException e) {
            System.err.println("Failed to render JSON: " + e.getMessage());
        }
    }

    
    
    
    private boolean confirm(String message) {
        return prompt.confirm(message, cmdParser.isAssumeYes());
    }

    private RepoRef parseRepo(String value) {
        try {
            return RepoRef.parse(value);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return null;
        }
    }

    private ReleaseProvider resolveProvider(String name) {
        ProviderConfig config = providers.get(name).orElse(null);
        if (config == null) {
            System.err.println("Provider '" + name + "' is not configured.");
            return null;
        }
        var type = config.providerType().orElse(null);
        if (type == null) {
            System.err.println("Provider '" + name + "' has an unsupported type.");
            return null;
        }
        return switch (type) {
            case GITHUB -> new GithubProvider(
                "github".equals(config.name())
                    ? githubClient
                    : new GithubClient(config.resolvedApi(), config.tokenEnv()),
                config);
            case GITEA -> new GiteaProvider(config);
            case GITLAB -> new GitLabProvider(config);
        };
    }

    
        // search across the configured providers
    private boolean search(Command command) {
        if (!loadSearchConfig()) {
            return false;
        }
        return new SearchCommand(
            cmdParser, githubClient, searchService, toolConfig, providers,
            this::resolveProvider, this::printJson).run(command);
    }

    private boolean loadSearchConfig() {
        try {
            toolConfig = stateStore.loadConfig().config();
            providers = new ProviderRegistry(toolConfig.providers());
            return true;
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
            return false;
        }
    }

    private boolean install(Command command) {
        return installService().install(command);
    }

    // built per call so tests can inject their dependencies
    private InstallService installService() {
        return new InstallService(
            os, cmdParser, toolConfig, platformHandler, verifier, signatureVerifier,
            backupManager, stateStore, this::parseRepo, this::resolveProvider,
            this::recordManifest, githubClient::discardAsset, this::confirm,
            this::printJson);
    }

        
    
    private boolean recordManifest(AppManifest manifest) {
        try {
            stateStore.saveManifest(manifest);
        } catch (IOException e) {
            System.err.println("Installed, but failed to write manifest: " + e.getMessage());
            return false;
        }
        try {
            stateStore.rebuildIndex();
        } catch (IOException e) {
            System.err.println("Manifest saved, but failed to rebuild index: " + e.getMessage());
        }
        return true;
    }

    
    
    
    
    
    
    
    
    
        // remove one install and its state
    private boolean uninstallManifest(AppManifest manifest) {
        AppManifest.Uninstall uninstall = manifest.install().uninstall();
        String uninstallPath = uninstall != null && uninstall.path() != null
            ? uninstall.path()
            : manifest.install().path();
        String productCode = uninstall == null ? null : uninstall.productCode();
        boolean removed = productCode == null || productCode.isBlank()
            ? platformHandler.uninstall(Path.of(uninstallPath))
            : platformHandler.uninstall(Path.of(uninstallPath), productCode);
        if (!removed) {
            System.err.println("Failed to uninstall " + manifest.repo() + ".");
            return false;
        }

        try {
            if (!stateStore.removeManifest(
                new RepoRef(manifest.provider(), manifest.repo()))) {
                System.err.println(
                    "Uninstalled " + manifest.repo() + ", but failed to remove its manifest.");
                return false;
            }
            stateStore.deleteApplicationState(
                new RepoRef(manifest.provider(), manifest.repo()));
        } catch (IOException e) {
            System.err.println(
                "Uninstalled " + manifest.repo() + ", but failed to update state: "
                    + e.getMessage());
            return false;
        }
        return true;
    }

    private boolean uninstall(Command command) {
        RepoRef ref = parseRepo(command.argument);
        if (ref == null) {
            return false;
        }

        AppManifest manifest;
        try {
            var found = stateStore.findManifest(ref);
            if (found.isEmpty()) {
                System.err.println("Release " + ref.display() + " is not found.");
                return false;
            }
            manifest = found.get();
        } catch (IOException e) {
            System.err.println("Failed to load application state: " + e.getMessage());
            return false;
        }

        String uninstallPath = manifest.install().uninstall() != null
            && manifest.install().uninstall().path() != null
            ? manifest.install().uninstall().path()
            : manifest.install().path();
        if (cmdParser.isDryRun()) {
            if (cmdParser.isJson()) {
                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("dry_run", true);
                plan.put("action", "uninstall");
                plan.put("provider", ref.provider());
                plan.put("repo", ref.path());
                plan.put("path", uninstallPath);
                printJson(plan);
            } else {
                System.out.println("Dry run: would uninstall " + ref.display() + ".");
                System.out.println("  Path: " + uninstallPath);
                System.out.println("No changes were made.");
            }
            return true;
        }

        if (!uninstallManifest(manifest)) {
            return false;
        }
        try {
            stateStore.rebuildIndex();
        } catch (IOException e) {
            System.err.println("Uninstalled " + ref.display()
                + ", but failed to rebuild the index: " + e.getMessage());
            return false;
        }
        if (cmdParser.isJson()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider", ref.provider());
            result.put("repo", ref.path());
            result.put("path", uninstallPath);
            printJson(Map.of("uninstalled", result));
        } else {
            Output.info("Successfully uninstalled.");
        }
        return true;
    }

    private boolean providers(Command command) {
        return new ProvidersCommand(
            cmdParser, stateStore, toolConfig, providers,
            this::printJson, this::confirm, this::uninstallManifest).run(command);
    }

    private boolean update(Command command) {
        return installService().update(command);
    }

    
    // every tracked app, one per line
    private boolean list() {
        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            System.err.println("Failed to load applications: " + e.getMessage());
            return false;
        }
        if (manifests.isEmpty() && !cmdParser.isJson()) {
            System.out.println("No releases installed.");
            return true;
        }
        if (cmdParser.isJson()) {
            ManifestViews views = new ManifestViews(platformHandler);
            List<Map<String, Object>> apps = new ArrayList<>();
            for (AppManifest manifest : manifests) {
                apps.add(views.listEntry(manifest));
            }
            printJson(Map.of("apps", apps));
            return true;
        }
        for (AppManifest manifest : manifests) {
            String version = manifest.version() == null ? "unknown" : manifest.version();
            String name = manifest.provider() + ":" + manifest.repo();
            System.out.println(name + " " + version);
        }
        return true;
    }



    private boolean info(Command command) {        RepoRef ref = parseRepo(command.argument);
        if (ref == null) {
            return false;
        }
        AppManifest manifest;
        try {
            var found = stateStore.findManifest(ref);
            if (found.isEmpty()) {
                System.err.println("Release " + ref.display() + " is not found.");
                return false;
            }
            manifest = found.get();
        } catch (IOException e) {
            System.err.println("Failed to load application state: " + e.getMessage());
            return false;
        }

        if (cmdParser.isJson()) {
            printJson(new ManifestViews(platformHandler).infoEntry(manifest));
            return true;
        }

        System.out.println("Repository: " + manifest.repo());
        System.out.println("Provider: " + manifest.provider());
        System.out.println("Version: "
            + (manifest.version() == null ? "unknown" : manifest.version()));
        System.out.println("Installed: " + manifest.installedAt());
        System.out.println("Updated: " + manifest.updatedAt());
        System.out.println("Path: " + manifest.install().path());
        System.out.println("Install state: " + platformHandler.installState(manifest)
            .name().toLowerCase(Locale.ROOT));
        System.out.println("Asset: " + manifest.asset().name());
        System.out.println("Checksum: " + ManifestViews.describeVerification(manifest.verification()));
        System.out.println("Signature: " + ManifestViews.describePinnedSignature(manifest.signature()));
        if (manifest.install().uninstall() != null) {
            String productCode = manifest.install().uninstall().productCode();
            if (productCode != null && !productCode.isBlank()) {
                System.out.println("Product code: " + productCode);
            }
        }
        List<AppManifest.Backup> backups = manifest.backups() == null
            ? List.of()
            : manifest.backups();
        System.out.println("Backups: " + backups.size());
        for (AppManifest.Backup backup : backups) {
            System.out.println("  - "
                + (backup.version() == null ? "unknown" : backup.version())
                + " (" + backup.createdAt() + ") " + backup.path());
        }
        return true;
    }



    private boolean rollback(Command command) {
        return new RollbackService(
            os, cmdParser, stateStore, backupManager, platformHandler,
            this::parseRepo, this::recordManifest, this::confirm,
            this::printJson).rollback(command);
    }

    private void applyInstallRoot() {
        String root = toolConfig.installRoot();
        if (root == null || root.isBlank()) {
            return;
        }
        try {
            platformHandler.setInstallRoot(Path.of(root));
        } catch (InvalidPathException e) {
            System.err.println("Ignoring invalid install_root: " + root);
        }
    }

    private boolean doctor() {
        return new Doctor(
            os, cmdParser, stateStore, platformHandler, signatureVerifier,
            this::printJson).run();
    }

    private boolean adopt(Command command) {
        if (os != Platform.OS.LINUX) {
            System.err.println("adopt is currently supported on Linux only.");
            return false;
        }
        RepoRef ref = parseRepo(command.argument);
        if (ref == null) {
            return false;
        }
        Path liveRoot = platformHandler.getInstallRoot();
        if (liveRoot == null) {
            System.err.println("Cannot determine the install root.");
            return false;
        }
        Path live = liveRoot.resolve(FileUtils.installName(ref.provider(), ref.path()));
        if (!Files.isRegularFile(live)) {
            System.err.println("No installed file found at " + live + ".");
            return false;
        }
        try {
            if (stateStore.findManifest(ref).isPresent()) {
                System.err.println("Release " + ref.display() + " is already tracked.");
                return false;
            }
        } catch (IOException e) {
            System.err.println("Failed to read application state: " + e.getMessage());
            return false;
        }

        String message = "Adopt " + live + " as " + ref.display()
            + "?\nVersion and checksum are unknown.";
        if (!confirm(message)) {
            System.err.println("Adoption declined.");
            return false;
        }

        try {
            String name = live.getFileName().toString();
            ProviderConfig providerConfig = providers.get(ref.provider()).orElse(null);
            AppManifest manifest = new AppManifest(
                AppManifest.KIND,
                AppManifest.SCHEMA_VERSION,
                ref.provider(),
                ref.path(),
                null,
                null,
                new AppManifest.Asset(
                    name,
                    live.toUri().toString(),
                    null),
                new AppManifest.Install(
                    InstallService.installKind(name),
                    live.toString(),
                    stateStore.paths().appDir(ref.provider(), ref.path()).toString(),
                    new AppManifest.Uninstall(live.toString(), List.of(), null)),
                new AppManifest.Verification("unknown", "none", null, null, null),
                List.of(),
                Files.getLastModifiedTime(live).toInstant(),
                Instant.now(),
                null,
                providerConfig == null ? null : providerConfig.type(),
                providerConfig == null ? null : providerConfig.resolvedApi());
            if (!recordManifest(manifest)) {
                return false;
            }
            if (cmdParser.isJson()) {
                printJson(Map.of("adopted", new ManifestViews(platformHandler).listEntry(manifest)));
            } else {
                Output.info("Adopted " + ref.display() + " at " + live + ".");
            }
            return true;
        } catch (IOException e) {
            System.err.println("Failed to adopt " + ref.display() + ": " + e.getMessage());
            return false;
        }
    }

    // usage on stdout
    private void help() {
        printUsage(System.out);
    }

    // unknown command: message plus usage on stderr
    private int usage(Command command) {
        if (command.argument != null && !command.argument.isBlank()) {
            System.err.println("Unknown command: " + command.argument);
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
        out.println("  update [name] - update the installed release by name");
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
        out.println("  --version <tag> - install or update to a specific release");
        out.println("  --dry-run - show what would happen without changing anything");
        out.println("  --json - machine-readable output");
        out.println("  --quiet - suppress progress messages");
        out.println("  --yes - assume yes for confirmation prompts");
    }
}
