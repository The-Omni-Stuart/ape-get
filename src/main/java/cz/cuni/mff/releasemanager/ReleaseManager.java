package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.ConsolePrompt;
import cz.cuni.mff.releasemanager.cmd.Prompt;
import cz.cuni.mff.releasemanager.platform.PathEnvironment;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.GithubProvider;
import cz.cuni.mff.releasemanager.provider.GitLabProvider;
import cz.cuni.mff.releasemanager.provider.GiteaProvider;
import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.ProviderDetector;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.provider.ProviderType;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.provider.SearchService;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.GrmIndex;
import cz.cuni.mff.releasemanager.state.StateLock;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.types.SearchResult;
import cz.cuni.mff.releasemanager.utils.DiskSpace;
import cz.cuni.mff.releasemanager.utils.Json;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.verify.AssetVerifier;
import cz.cuni.mff.releasemanager.verify.SignatureResult;
import cz.cuni.mff.releasemanager.verify.SignatureStatus;
import cz.cuni.mff.releasemanager.verify.SignatureVerifier;
import cz.cuni.mff.releasemanager.verify.SignatureVerifiers;
import cz.cuni.mff.releasemanager.verify.VerificationResult;
import cz.cuni.mff.releasemanager.verify.VerificationStatus;

/**
 * ReleaseManager class manages GitHub release installation, uninstallation,
 * searching, updating, and listing of installed releases.
 *
 * <p>This class provides a command-line interface for interacting with GitHub
 * repositories and handling platform-specific release installation logic.
 * It acts as the central coordinator for parsing commands, accessing GitHub APIs,
 * and manipulating local release data.</p>
 *
 * Supported commands include:
 * <ul>
 *   <li>search</li>
 *   <li>install</li>
 *   <li>uninstall</li>
 *   <li>update</li>
 *   <li>list</li>
 *   <li>help</li>
 * </ul>
 */
public class ReleaseManager {
    private static final long MIN_FREE_BYTES = 100L * 1024 * 1024;

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
    /**
     * @return the singleton {@code ReleaseManager} instance
     */
    public static ReleaseManager getInstance() {
        if (instance == null) {
            instance = new ReleaseManager();
        }
        return instance;
    }
    /**
     * Executes the command based on the provided arguments.
     *
     * @param args command-line arguments specifying the command and parameters
     * @return process exit code: 0 success, 1 failure, 2 usage error
     */
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
        ConfigStore.LoadResult config;
        try {
            config = stateStore.loadConfig();
        } catch (IOException e) {
            System.err.println("Failed to initialize state at "
                + stateStore.paths().root() + ": " + e.getMessage());
            return false;
        }
        toolConfig = config.config();
        providers = new ProviderRegistry(toolConfig.providers());
        applyInstallRoot();

        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            System.err.println("Failed to read application manifests: " + e.getMessage());
            return false;
        }

        Optional<GrmIndex> index;
        try {
            index = stateStore.loadIndex();
        } catch (IOException e) {
            System.err.println("Failed to read index: " + e.getMessage());
            return false;
        }

        if (index.isEmpty()) {
            try {
                stateStore.rebuildIndex();
            } catch (IOException e) {
                System.err.println("Failed to create index at "
                    + stateStore.paths().indexFile() + ": " + e.getMessage());
                return false;
            }
            if (config.created()) {
                printSetup();
            } else {
                Output.info("Rebuilt index at " + stateStore.paths().indexFile());
            }
            return true;
        }

        boolean changed = false;
        for (StateStore.RecoveryIssue issue : stateStore.findRecoveryIssues(index.get(), manifests)) {
            if (issue.kind() == StateStore.RecoveryKind.RESTORE_MANIFEST) {
                if (!confirm(restoreMessage(issue.entry()))) {
                    System.err.println("Manifest recovery declined for " + issue.entry().repo() + ".");
                    return false;
                }
                try {
                    stateStore.saveManifest(issue.entry().snapshot());
                } catch (IOException e) {
                    System.err.println("Failed to restore manifest for "
                        + issue.entry().repo() + ": " + e.getMessage());
                    return false;
                }
                Output.info("Restored manifest for " + issue.entry().repo() + ".");
                changed = true;
            } else {
                if (!confirm(dropMessage(issue.entry()))) {
                    System.err.println("Stale index entry kept for " + issue.entry().repo() + ".");
                    return false;
                }
                Output.info("Removed stale index entry for " + issue.entry().repo() + ".");
                changed = true;
            }
        }

        Set<String> indexedRepos = repos(index.get());
        Set<String> manifestRepos = new HashSet<>();
        for (AppManifest manifest : manifests) {
            if (manifest.repo() != null) {
                manifestRepos.add(manifest.repo());
            }
        }
        if (changed || !indexedRepos.equals(manifestRepos)) {
            try {
                stateStore.rebuildIndex();
            } catch (IOException e) {
                System.err.println("Failed to rebuild index: " + e.getMessage());
                return false;
            }
        }

        if (config.created()) {
            printSetup();
        }
        return true;
    }

    private void printSetup() {
        Output.info("First-time setup complete.");
        Output.info("  config: " + stateStore.paths().configFile());
        Output.info("  index:  " + stateStore.paths().indexFile());
    }

    private void printJson(Object value) {
        try {
            Output.data(Json.write(value));
        } catch (JsonProcessingException e) {
            System.err.println("Failed to render JSON: " + e.getMessage());
        }
    }

    private static Set<String> repos(GrmIndex index) {
        Set<String> repos = new HashSet<>();
        for (GrmIndex.Entry entry : index.apps()) {
            if (entry != null && entry.repo() != null) {
                repos.add(entry.repo());
            }
        }
        return repos;
    }

    private String restoreMessage(GrmIndex.Entry entry) {
        String provider = entry.snapshot() != null && entry.snapshot().provider() != null
            ? entry.snapshot().provider()
            : "github";
        return "The manifest for " + entry.repo() + " is missing:\n  "
            + stateStore.paths().manifestFile(provider, entry.repo())
            + "\nThe index has a recovery snapshot and the app is installed at:\n  "
            + entry.installPath()
            + "\nRestore the manifest from the snapshot?";
    }

    private static String dropMessage(GrmIndex.Entry entry) {
        return "The index contains an entry for " + entry.repo()
            + ", but its manifest is missing.\nRemove the stale index entry?";
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

    private ReleaseAssets latest(ReleaseProvider provider, String path) {
        String version = cmdParser.getVersion();
        try {
            return version == null || version.isBlank()
                ? provider.latest(path)
                : provider.release(path, version);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("Failed to read release: " + e.getMessage());
            return null;
        }
    }

    private Path installDownloaded(
        ReleaseProvider provider, Path downloaded, RepoRef ref, String version) {
        Path installed = null;
        try {
            installed = platformHandler.install(
                new cz.cuni.mff.releasemanager.platform.InstallRequest(
                    downloaded, ref.provider(), ref.path(), version));
        } finally {
            if (!downloaded.equals(installed)) {
                provider.discardAsset(downloaded);
            }
        }
        return installed;
    }
    /**
     * Searches for a GitHub repository by name and prints the results.
     *
     * @param command the command containing the search term
     */
    private boolean search(Command command) {
        if (!loadSearchConfig()) {
            return false;
        }
        String providerName = cmdParser.getProvider();
        if (providerName == null || providerName.isBlank()) {
            providerName = toolConfig.searchAllEnabled() ? "all" : "github";
        }

        if ("all".equalsIgnoreCase(providerName)) {
            List<ReleaseProvider> adapters = new ArrayList<>();
            for (ProviderConfig config : providers.all()) {
                ReleaseProvider provider = resolveProvider(config.name());
                if (provider != null) {
                    adapters.add(provider);
                }
            }
            adapters.sort(java.util.Comparator.comparing(provider -> provider.config().name()));
            if (adapters.isEmpty()) {
                System.err.println("No providers are configured.");
                return false;
            }
            Output.info("Searching " + adapters.size() + " provider(s)...");
            return printSearchResults(searchService.searchAll(
                adapters, command.argument, Duration.ofSeconds(10)));
        }

        SearchResult result;
        if ("github".equalsIgnoreCase(providerName)) {
            var searchResult = githubClient.searchRepoByName(command.argument);
            if (searchResult.isEmpty()) {
                return false;
            }
            result = searchResult.get();
        } else {
            ReleaseProvider provider = resolveProvider(providerName);
            if (provider == null) {
                return false;
            }
            try {
                result = provider.search(command.argument);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                System.err.println("Search failed: " + e.getMessage());
                return false;
            }
        }
        List<Repo> items = result.items() == null ? List.of() : result.items();
        if (cmdParser.isJson()) {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Repo repo : items) {
                results.add(searchEntry(providerName, repo));
            }
            printJson(Map.of("results", results));
            return true;
        }
        if (items.isEmpty()) {
            System.out.println("No results found.");
            return true;
        }
        System.out.println("Found " + items.size() + " repositories.");
        String prefix = "github".equalsIgnoreCase(providerName) ? "" : providerName + ":";
        for (Repo repo : items) {
            System.out.println(prefix + repo.fullName() + ": " + repo.description());
        }
        Output.info("To install a release, use the 'install' command followed by the repository name.");
        return true;
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

    private boolean printSearchResults(List<SearchService.ProviderResult> results) {
        List<Map<String, Object>> hits = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        int succeeded = 0;
        for (SearchService.ProviderResult result : results) {
            if (result.error() != null) {
                errors.add(Map.of(
                    "provider", result.provider(),
                    "error", result.error()));
                continue;
            }
            succeeded++;
            for (Repo repo : result.repos()) {
                hits.add(searchEntry(result.provider(), repo));
            }
        }
        if (cmdParser.isJson()) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("results", hits);
            report.put("errors", errors);
            printJson(report);
            return succeeded > 0;
        }
        for (Map<String, Object> error : errors) {
            System.err.println("Warning: " + error.get("provider") + ": "
                + error.get("error"));
        }
        if (succeeded == 0) {
            System.err.println("All provider searches failed.");
            return false;
        }
        if (hits.isEmpty()) {
            System.out.println("No results found.");
            return true;
        }
        for (Map<String, Object> hit : hits) {
            System.out.println(hit.get("provider") + ":" + hit.get("repo") + ": "
                + (hit.get("description") == null ? "" : hit.get("description")));
        }
        System.out.println("Found " + hits.size() + " repositories across "
            + succeeded + " provider(s).");
        Output.info("Use provider:owner/repo to install one of them.");
        return true;
    }

    private static Map<String, Object> searchEntry(String provider, Repo repo) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("provider", provider);
        entry.put("repo", repo.fullName());
        entry.put("description", repo.description());
        return entry;
    }

    /**
     * Installs the latest release of a specified GitHub repository.
     * If multiple assets are found, the user is prompted to select one.
     *
     * @param command the command containing the repository name
     */
    private boolean install(Command command) {
        RepoRef ref = parseRepo(command.argument);
        if (ref == null) {
            return false;
        }
        ReleaseProvider provider = resolveProvider(ref.provider());
        if (provider == null) {
            return false;
        }
        AppManifest previous;
        try {
            previous = stateStore.findManifest(ref).orElse(null);
        } catch (IOException e) {
            System.err.println("Failed to load application state: " + e.getMessage());
            return false;
        }
        ReleaseAssets release = latest(provider, ref.path());
        if (release == null || release.installable().isEmpty()) {
            System.err.println("Failed to retrieve the release.");
            return false;
        }
        Asset asset = selectAsset(release.installable());
        if (asset == null) {
            return false;
        }
        if (cmdParser.isDryRun()) {
            printInstallPlan(ref, release, asset);
            return true;
        }
        Path downloadedAsset = download(provider, asset);
        if (downloadedAsset == null) {
            return false;
        }
        VerificationResult verification = verify(provider, asset, release.all(), downloadedAsset);
        if (!isAllowed(verification, downloadedAsset)) {
            return false;
        }
        SignatureResult signature = verifySignature(
            provider, asset, release.all(), downloadedAsset);
        if (!isSignatureAllowed(signature, downloadedAsset, previous)) {
            return false;
        }
        if (!isSignerAllowed(previous, signature, downloadedAsset, "install")) {
            return false;
        }
        Path installedAsset = installDownloaded(
            provider, downloadedAsset, ref, release.version());
        if (installedAsset == null) {
            System.err.println("Installation failed.");
            return false;
        }
        AppManifest manifest = createManifest(
            ref, provider.config(), release, asset, installedAsset, verification,
            signature, Instant.now(), List.of());
        if (!recordManifest(manifest)) {
            return false;
        }
        if (cmdParser.isJson()) {
            printJson(Map.of("installed",
                releaseResult(ref, release, asset, installedAsset)));
        } else {
            Output.info("Installation successful.");
        }
        return true;
    }
    private Path download(ReleaseProvider provider, Asset asset) {
        try {
            return provider.download(asset);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("Failed to download " + asset.name() + ": " + e.getMessage());
            return null;
        }
    }

    private VerificationResult verify(
        ReleaseProvider provider, Asset asset, List<Asset> releaseAssets, Path downloaded) {
        try {
            return verifier.verify(provider, asset, releaseAssets, downloaded);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("Checksum verification failed: " + e.getMessage());
            return null;
        }
    }

    private boolean isAllowed(VerificationResult verification, Path downloaded) {
        if (verification == null) {
            githubClient.discardAsset(downloaded);
            return false;
        }
        boolean strict = cmdParser.isStrict() || toolConfig.strictVerification();
        if (!verification.allowsInstall(strict)) {
            String action = verification.status() == VerificationStatus.MISMATCH
                ? " Refusing to install."
                : " Refusing to install (strict mode).";
            System.err.println(verification.message() + action);
            githubClient.discardAsset(downloaded);
            return false;
        }
        if (verification.status() != VerificationStatus.VERIFIED) {
            System.err.println(verification.message() + " Continuing without verification.");
        }
        return true;
    }

    private SignatureResult verifySignature(
        ReleaseProvider provider, Asset asset, List<Asset> releaseAssets, Path downloaded) {
        try {
            return signatureVerifier.verify(provider, asset, releaseAssets, downloaded);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return SignatureResult.error(asset.name(), e.getMessage());
        }
    }

    private boolean isSignatureAllowed(
        SignatureResult signature, Path downloaded, AppManifest previous) {
        if (signature == null) {
            githubClient.discardAsset(downloaded);
            return false;
        }
        boolean pinned = previous != null && previous.signature() != null
            && previous.signature().isVerified();
        boolean required = cmdParser.isRequireSignature() || toolConfig.requireSignature();
        if (!signature.allowsInstall(required)) {
            System.err.println(signature.message() + " Refusing to install.");
            githubClient.discardAsset(downloaded);
            return false;
        }
        if (signature.status() == SignatureStatus.VERIFIED) {
            Output.info(signature.message());
        } else if (!pinned) {
            System.err.println(
                signature.message() + " Continuing without a verified signature.");
        }
        return true;
    }

    private boolean isSignerAllowed(
        AppManifest previous, SignatureResult signature, Path downloaded, String operation) {
        if (previous == null || previous.signature() == null
            || !previous.signature().isVerified()) {
            return true;
        }
        AppManifest.Signature pinned = previous.signature();
        boolean unsigned = signature == null
            || signature.status() != SignatureStatus.VERIFIED
            || signature.identity() == null;
        boolean changed = !unsigned
            && !pinned.identity().equalsIgnoreCase(signature.identity());
        if (!unsigned && !changed) {
            return true;
        }

        String reason = unsigned
            ? "it is pinned to " + describe(pinned)
                + ", but no verified signature is available"
            : "the signer changed from " + describe(pinned)
                + " to " + describe(signature);
        if (!cmdParser.isAllowSignerChange()) {
            System.err.println("Unable to " + operation + " " + previous.repo() + ".");
            System.err.println("Reason: " + reason + ".");
            System.err.println(
                "Be careful: this can mean the release was replaced or tampered with.");
            System.err.println(unsigned
                ? "If you trust this release, rerun with --allow-signer-change "
                    + "to clear the pinned signer."
                : "If you trust the new signer, rerun with --allow-signer-change "
                    + "to accept it and update the pin.");
            githubClient.discardAsset(downloaded);
            return false;
        }

        String question = unsigned
            ? "Clear the signer pin for " + previous.repo() + "?\n"
                + "  pinned: " + describe(pinned) + "\n"
                + "  now:    no verified signature\n"
                + "Only continue if you trust this release."
            : "Accept the signer change for " + previous.repo() + "?\n"
                + "  from: " + describe(pinned) + "\n"
                + "  to:   " + describe(signature) + "\n"
                + "Only continue if you trust the new signer.";
        if (!confirm(question)) {
            System.err.println("Signer override declined.");
            githubClient.discardAsset(downloaded);
            return false;
        }
        if (unsigned) {
            System.err.println("Warning: signer pin cleared for " + previous.repo() + ".");
        } else {
            System.err.println("Warning: signer pin updated for " + previous.repo()
                + " to " + describe(signature) + ".");
        }
        return true;
    }

    private static String describe(AppManifest.Signature signature) {
        String signer = signature.signer() == null ? "unknown signer" : signature.signer();
        return signature.identity() == null
            ? signer
            : signer + " (" + signature.identity() + ")";
    }

    private static String describe(SignatureResult signature) {
        String signer = signature.signer() == null ? "unknown signer" : signature.signer();
        return signature.identity() == null
            ? signer
            : signer + " (" + signature.identity() + ")";
    }

    private static AppManifest.Signature signatureRecord(SignatureResult signature) {
        if (signature == null) {
            return null;
        }
        return new AppManifest.Signature(
            signature.status().name().toLowerCase(Locale.ROOT),
            signature.mechanism(),
            signature.signer(),
            signature.identity(),
            signature.notarized());
    }

    private AppManifest createManifest(
        RepoRef ref,
        ProviderConfig provider,
        ReleaseAssets release,
        Asset asset,
        Path installed,
        VerificationResult verification,
        SignatureResult signature,
        Instant installedAt,
        List<AppManifest.Backup> backups
    ) {
        Path launcher = platformHandler.launcherPath(ref.path());
        Path uninstallPath = launcher == null ? installed : launcher;
        return new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            ref.provider(),
            ref.path(),
            release.version(),
            release.releaseId(),
            new AppManifest.Asset(
                asset.name(),
                asset.url(),
                null,
                verification.status() == VerificationStatus.VERIFIED
                    ? verification.expected()
                    : null),
            new AppManifest.Install(
                installKind(asset),
                installed.toString(),
                stateStore.paths().appDir(ref.provider(), ref.path()).toString(),
                new AppManifest.Uninstall(
                    uninstallPath.toString(), List.of(),
                    platformHandler.productCode(installed))),
            new AppManifest.Verification(
                verification.status().name().toLowerCase(Locale.ROOT),
                "sha256",
                verification.checksumAssetName(),
                verification.expected(),
                verification.actual()),
            backups,
            installedAt,
            Instant.now(),
            signatureRecord(signature),
            provider.type(),
            provider.resolvedApi());
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

    private static String installKind(Asset asset) {
        return installKind(asset.name());
    }

    private static String installKind(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".appimage")) {
            return "appimage";
        }
        if (name.endsWith(".msi")) {
            return "msi";
        }
        if (name.endsWith(".exe")) {
            return "exe";
        }
        if (name.endsWith(".dmg")) {
            return "dmg";
        }
        return "unknown";
    }

    private void printInstallPlan(RepoRef ref, ReleaseAssets release, Asset asset) {
        printPlan("install", ref, release, asset);
    }

    private void printUpdatePlan(RepoRef ref, ReleaseAssets release, Asset asset) {
        printPlan("update", ref, release, asset);
    }

    private void printPlan(String action, RepoRef ref, ReleaseAssets release, Asset asset) {
        Path launcher = platformHandler.launcherPath(ref.path());
        if (cmdParser.isJson()) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("dry_run", true);
            plan.put("action", action);
            plan.put("provider", ref.provider());
            plan.put("repo", ref.path());
            plan.put("version", release.version());
            plan.put("asset", asset.name());
            plan.put("source", asset.url());
            plan.put("launcher", launcher == null ? null : launcher.toString());
            printJson(plan);
            return;
        }
        System.out.println("Dry run: would " + action + " " + ref.display()
            + (action.equals("install") ? " " : " to ") + versionLabel(release) + ".");
        System.out.println("  Asset:  " + asset.name());
        System.out.println("  Source: " + asset.url());
        if (launcher != null) {
            System.out.println("  Launcher: " + launcher);
        }
        System.out.println("No changes were made.");
    }

    private Map<String, Object> releaseResult(
        RepoRef ref, ReleaseAssets release, Asset asset, Path installed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", ref.provider());
        result.put("repo", ref.path());
        result.put("version", release.version());
        result.put("asset", asset.name());
        result.put("path", installed == null ? null : installed.toString());
        Path launcher = platformHandler.launcherPath(ref.path());
        result.put("launcher", launcher == null ? null : launcher.toString());
        return result;
    }

    private static String versionLabel(ReleaseAssets release) {
        return release.version() == null ? "(unknown version)" : release.version();
    }

    private Asset selectAsset(List<Asset> assets) {        String requested = cmdParser.getAsset();
        if (requested == null || requested.isBlank()) {
            return getSingleAsset(assets);
        }
        String value = requested.trim();
        if (value.matches("\\d+")) {
            int index;
            try {
                index = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                index = -1;
            }
            if (index >= 1 && index <= assets.size()) {
                return assets.get(index - 1);
            }
        }
        for (Asset asset : assets) {
            if (asset.name().equalsIgnoreCase(value)) {
                return asset;
            }
        }
        System.err.println("No installable asset named '" + value + "'. Available assets:");
        for (int i = 0; i < assets.size(); i++) {
            System.err.println("  " + (i + 1) + ": " + assets.get(i).name());
        }
        return null;
    }

    /**
     * Prompts the user to choose a single asset from a list of assets.
     *
     * @param assets list of available release assets
     * @return the selected asset
     */
    private Asset getSingleAsset(List<Asset> assets) {
        if (assets.size() == 1) {
            return assets.get(0);
        }
        System.out.println("Multiple assets found. Please select one:");
        for (int i = 0; i < assets.size(); i++) {
            System.out.println((i + 1) + ": " + assets.get(i).name());
        }
        int choice = getChoice(1, assets.size());
        return assets.get(choice - 1);
    }
    /**
     * Prompts the user for a choice between a minimum and maximum integer value.
     *
     * @param min the minimum valid choice
     * @param max the maximum valid choice
     * @return the user's choice
     */
    private int getChoice(int min, int max) {
        int input = cmdParser.getUserInput();
        if (input < min || input > max) {
            System.err.println("Invalid choice. Valid options are: " + min + " - " + max);
            return getChoice(min, max);
        }
        return input;
    }
    /**
     * Uninstalls a previously installed release based on the repository name.
     *
     * @param command the command containing the repository name
     */
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

    /**
     * Updates an installed release if a newer version is available.
     *
     * @param command the command containing the repository name
     */
    private boolean providers(Command command) {
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
        System.err.println("Usage:");
        System.err.println("  providers list");
        System.err.println("  providers show <name>");
        System.err.println("  providers add <name> <url> [--type auto|github|gitea|gitlab]"
            + " [--api URL] [--token-env VAR]");
        System.err.println("  providers update <name> [--type ...] [--api URL] [--url URL]"
            + " [--token-env VAR] [--force]");
        System.err.println("  providers remove <name> [--abandon | --purge]");
        System.err.println("  providers recover <name> [--type ...] [--api URL]");
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

    private Map<String, Object> providerEntry(ProviderConfig config) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", config.name());
        entry.put("type", config.type());
        entry.put("url", config.url());
        entry.put("api", config.resolvedApi());
        entry.put("token_env", config.tokenEnv());
        entry.put("built_in", ProviderRegistry.builtIns().containsKey(config.name()));
        return entry;
    }

    private boolean providersList() {
        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            System.err.println("Failed to read installed packages: " + e.getMessage());
            return false;
        }
        List<ProviderConfig> configs = new ArrayList<>(providers.all());
        configs.sort(java.util.Comparator.comparing(ProviderConfig::name));
        List<Map<String, Object>> entries = new ArrayList<>();
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
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", config.name());
                entry.put("type", config.type());
                entry.put("url", config.url());
                entry.put("api", config.resolvedApi());
                entry.put("token_env", config.tokenEnv());
                entry.put("token_state", tokenState);
                entry.put("built_in", builtIn);
                entry.put("packages", count);
                entries.add(entry);
                continue;
            }
            String token = config.tokenEnv() == null || config.tokenEnv().isBlank()
                ? ""
                : " token-env=" + config.tokenEnv() + " (" + tokenState + ")";
            System.out.println(config.name() + " (" + config.type() + ", "
                + config.resolvedApi() + ") packages=" + count
                + (builtIn ? " [built-in]" : "") + token);
        }
        if (cmdParser.isJson()) {
            printJson(Map.of("providers", entries));
        }
        return true;
    }

    private boolean providersShow(String name) {
        ProviderConfig config = providers.get(name).orElse(null);
        if (config == null) {
            System.err.println("Provider '" + name + "' is not configured.");
            return false;
        }
        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests().stream()
                .filter(manifest -> config.name().equals(manifest.provider()))
                .toList();
        } catch (IOException e) {
            System.err.println("Failed to read installed packages: " + e.getMessage());
            return false;
        }
        boolean builtIn = ProviderRegistry.builtIns().containsKey(config.name());
        if (cmdParser.isJson()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", config.name());
            entry.put("type", config.type());
            entry.put("url", config.url());
            entry.put("api", config.resolvedApi());
            entry.put("token_env", config.tokenEnv());
            entry.put("built_in", builtIn);
            List<Map<String, Object>> packages = new ArrayList<>();
            for (AppManifest manifest : manifests) {
                packages.add(Map.of(
                    "repo", manifest.repo(),
                    "version", manifest.version() == null ? "" : manifest.version()));
            }
            entry.put("packages", packages);
            printJson(entry);
            return true;
        }
        System.out.println("Name: " + config.name());
        System.out.println("Type: " + config.type());
        System.out.println("URL: " + config.url());
        System.out.println("API: " + config.resolvedApi());
        System.out.println("Token env: "
            + (config.tokenEnv() == null || config.tokenEnv().isBlank()
                ? "none" : config.tokenEnv()));
        System.out.println("Built-in: " + builtIn);
        System.out.println("Packages: " + manifests.size());
        for (AppManifest manifest : manifests) {
            System.out.println("  " + config.name() + ":" + manifest.repo() + " "
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
            System.err.println(e.getMessage());
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
                    System.err.println(e.getMessage());
                    return false;
                }
            }
        }
        if (!ProviderConfig.validName(name)) {
            System.err.println("Invalid provider name: " + name);
            return false;
        }
        if (ProviderRegistry.builtIns().containsKey(name)) {
            System.err.println("Provider '" + name + "' is built-in; choose another name.");
            return false;
        }
        if (providers.get(name).isPresent()) {
            System.err.println("Provider '" + name + "' already exists; use providers update.");
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
                    System.err.println("Unknown provider type: " + typeValue);
                    return false;
                }
            }
        } catch (IOException e) {
            System.err.println(e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Provider detection interrupted.");
            return false;
        }

        ProviderConfig config = new ProviderConfig(
            name, type.id(), urlBase, api, options.values().get("token-env")).named(name);
        if (!config.isValid()) {
            System.err.println("Invalid provider config; check the HTTPS URL and API base.");
            return false;
        }
        Map<String, ProviderConfig> updated = currentProviders();
        updated.put(name, config);
        if (!saveProviders(updated)) {
            return false;
        }
        if (cmdParser.isJson()) {
            printJson(Map.of("provider", providerEntry(config)));
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
            System.err.println(e.getMessage());
            return false;
        }
        if (options.positional().size() != 1) {
            return providersUsage();
        }
        String name = options.positional().get(0);
        ProviderConfig existing = providers.get(name).orElse(null);
        if (existing == null) {
            System.err.println("Provider '" + name + "' is not configured.");
            return false;
        }
        if (ProviderRegistry.builtIns().containsKey(name)) {
            System.err.println("Provider '" + name + "' is built-in and cannot be updated.");
            return false;
        }

        String typeValue = options.values().getOrDefault("type", existing.type());
        ProviderType type = ProviderType.from(typeValue).orElse(null);
        if (type == null) {
            System.err.println("Unknown provider type: " + typeValue);
            return false;
        }
        String url = options.values().getOrDefault("url", existing.url());
        String api = options.values().get("api");
        if (api == null && (!type.id().equals(existing.type())
            || !url.equals(existing.url()))) {
            api = null; // let named() derive from the new type/url
        }
        String tokenEnv = options.values().containsKey("token-env")
            ? options.values().get("token-env") : existing.tokenEnv();
        ProviderConfig updatedConfig = new ProviderConfig(
            name, type.id(), url, api, tokenEnv).named(name);
        if (!updatedConfig.isValid()) {
            System.err.println("Invalid provider config; check the HTTPS URL and API base.");
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
                    System.err.println("Provider '" + name + "' has " + manifests.size()
                        + " installed package(s); changing type/API requires --force.");
                    return false;
                }
                if (!confirm("Change provider " + name + " from " + existing.resolvedApi()
                    + " to " + updatedConfig.resolvedApi()
                    + "? Installed packages will fetch updates from the new endpoint.")) {
                    System.err.println("Provider update declined.");
                    return false;
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read installed packages: " + e.getMessage());
            return false;
        }

        Map<String, ProviderConfig> map = currentProviders();
        map.put(name, updatedConfig);
        if (!saveProviders(map)) {
            return false;
        }
        if (cmdParser.isJson()) {
            printJson(Map.of("provider", providerEntry(updatedConfig)));
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
            System.err.println(e.getMessage());
            return false;
        }
        if (options.positional().size() != 1) {
            return providersUsage();
        }
        if (options.flags().contains("abandon") && options.flags().contains("purge")) {
            System.err.println("Use only one of --abandon or --purge.");
            return false;
        }
        String name = options.positional().get(0);
        ProviderConfig config = providers.get(name).orElse(null);
        if (config == null) {
            System.err.println("Provider '" + name + "' is not configured.");
            return false;
        }
        if (ProviderRegistry.builtIns().containsKey(name)) {
            System.err.println("Provider '" + name + "' is built-in and cannot be removed.");
            return false;
        }

        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests().stream()
                .filter(manifest -> name.equals(manifest.provider()))
                .toList();
        } catch (IOException e) {
            System.err.println("Failed to read installed packages: " + e.getMessage());
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
                System.err.println("Refusing to remove the provider while packages are installed.");
                System.err.println("  --purge     uninstall them, delete their state, then remove it");
                System.err.println("  --abandon   remove only the provider config; packages remain");
                return false;
            }
            if (options.flags().contains("purge")) {
                if (!confirm("Uninstall " + manifests.size() + " package(s) and remove provider "
                    + name + " (" + config.type() + ", " + config.resolvedApi() + ")?")) {
                    System.err.println("Provider removal declined.");
                    return false;
                }
                for (AppManifest manifest : manifests) {
                    if (!uninstallManifest(manifest)) {
                        System.err.println("Stopped; provider " + name + " was not removed.");
                        return false;
                    }
                }
                try {
                    stateStore.rebuildIndex();
                } catch (IOException e) {
                    System.err.println("Packages uninstalled, but the index could not be rebuilt: "
                        + e.getMessage());
                    return false;
                }
            } else {
                if (!confirm("Remove provider " + name + " and leave "
                    + manifests.size() + " package(s) orphaned?")) {
                    System.err.println("Provider removal declined.");
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
            printJson(Map.of("removed", name));
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
            System.err.println(e.getMessage());
            return false;
        }
        if (options.positional().size() != 1) {
            return providersUsage();
        }
        String name = options.positional().get(0);
        if (providers.get(name).isPresent()) {
            System.err.println("Provider '" + name + "' is already configured.");
            return false;
        }

        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests().stream()
                .filter(manifest -> name.equals(manifest.provider()))
                .toList();
        } catch (IOException e) {
            System.err.println("Failed to read installed packages: " + e.getMessage());
            return false;
        }
        if (manifests.isEmpty()) {
            System.err.println("No installed packages reference provider '" + name + "'.");
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
            System.err.println("Installed manifests do not record the provider type and API.");
            System.err.println("Pass --type and --api to recover this provider.");
            return false;
        }
        if (types.size() > 1 || apis.size() > 1) {
            System.err.println("Installed manifests disagree about this provider:");
            System.err.println("  types: " + types);
            System.err.println("  APIs:  " + apis);
            return false;
        }

        String typeValue = options.values().getOrDefault("type", types.iterator().next());
        ProviderType type = ProviderType.from(typeValue).orElse(null);
        if (type == null) {
            System.err.println("Unknown provider type: " + typeValue);
            return false;
        }
        String api = options.values().getOrDefault("api", apis.iterator().next());
        String url = deriveUrlFromApi(type, api);
        ProviderConfig config = new ProviderConfig(
            name, type.id(), url, api, options.values().get("token-env")).named(name);
        if (!config.isValid()) {
            System.err.println("Recovered provider config is invalid.");
            return false;
        }

        if (!confirm("Recover provider " + name + " as " + type.id() + " (" + api
            + ") for " + manifests.size() + " installed package(s)?")) {
            System.err.println("Provider recovery declined.");
            return false;
        }
        Map<String, ProviderConfig> map = currentProviders();
        map.put(name, config);
        if (!saveProviders(map)) {
            return false;
        }
        if (cmdParser.isJson()) {
            printJson(Map.of("provider", providerEntry(config)));
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
            map);
        try {
            stateStore.saveConfig(updated);
        } catch (IOException e) {
            System.err.println("Failed to save providers: " + e.getMessage());
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

    private boolean update(Command command) {
        RepoRef ref = parseRepo(command.argument);
        if (ref == null) {
            return false;
        }
        AppManifest previous;
        try {
            var found = stateStore.findManifest(ref);
            if (found.isEmpty()) {
                System.err.println("Release " + ref.display() + " is not found.");
                return false;
            }
            previous = found.get();
        } catch (IOException e) {
            System.err.println("Failed to load application state: " + e.getMessage());
            return false;
        }
        ReleaseProvider provider = resolveProvider(previous.provider());
        if (provider == null) {
            return false;
        }

        ReleaseAssets latest = latest(provider, previous.repo());
        if (latest == null) {
            return false;
        }
        List<Asset> assets = latest.installable();
        if (assets.isEmpty()) {
            System.err.println("No asset found.");
            return false;
        }
        Asset newAsset = null;
        String requestedAsset = cmdParser.getAsset();
        if (requestedAsset != null && !requestedAsset.isBlank()) {
            newAsset = selectAsset(assets);
            if (newAsset == null) {
                return false;
            }
        } else {
            for (Asset asset : assets) {
                if (asset.name().equals(previous.asset().name())) {
                    newAsset = asset;
                    break;
                }
            }
            if (newAsset == null) {
                newAsset = getSingleAsset(assets);
            }
        }
        if (previous.asset().url().equals(newAsset.url())) {
            Output.info("Already up to date.");
            return true;
        }
        if (cmdParser.isDryRun()) {
            printUpdatePlan(ref, latest, newAsset);
            return true;
        }

        Path downloadedAsset = download(provider, newAsset);
        if (downloadedAsset == null) {
            return false;
        }
        VerificationResult verification = verify(provider, newAsset, latest.all(), downloadedAsset);
        if (!isAllowed(verification, downloadedAsset)) {
            return false;
        }
        SignatureResult signature = verifySignature(
            provider, newAsset, latest.all(), downloadedAsset);
        if (!isSignatureAllowed(signature, downloadedAsset, previous)) {
            return false;
        }
        if (!isSignerAllowed(previous, signature, downloadedAsset, "update")) {
            return false;
        }

        List<AppManifest.Backup> backups = previous.backups() == null
            ? List.of()
            : previous.backups();
        boolean backupSupported = os != Platform.OS.WINDOWS
            || platformHandler.supportsInstallerRollback(previous);
        if (toolConfig.backupCount() > 0 && backupSupported) {
            try {
                backups = backupManager.createAndPrune(
                    previous, toolConfig.backupCount());
            } catch (IOException e) {
                System.err.println("Failed to create backup; update aborted: " + e.getMessage());
                provider.discardAsset(downloadedAsset);
                return false;
            }
        }

        Path installedAsset = installDownloaded(
            provider, downloadedAsset, ref, latest.version());
        if (installedAsset == null) {
            System.err.println("Installation failed.");
            return false;
        }

        AppManifest updated = createManifest(
            ref,
            provider.config(),
            latest,
            newAsset,
            installedAsset,
            verification,
            signature,
            previous.installedAt(),
            backups);
        if (!recordManifest(updated)) {
            return false;
        }
        if (cmdParser.isJson()) {
            printJson(Map.of("updated",
                releaseResult(ref, latest, newAsset, installedAsset)));
        } else {
            Output.info("Successfully updated.");
        }
        return true;
    }

    /**
     * Lists all installed releases.
     */
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
            List<Map<String, Object>> apps = new ArrayList<>();
            for (AppManifest manifest : manifests) {
                apps.add(listEntry(manifest));
            }
            printJson(Map.of("apps", apps));
            return true;
        }
        for (AppManifest manifest : manifests) {
            String version = manifest.version() == null ? "unknown" : manifest.version();
            String name = RepoRef.DEFAULT_PROVIDER.equals(manifest.provider())
                ? manifest.repo()
                : manifest.provider() + ":" + manifest.repo();
            System.out.println(name + " " + version);
        }
        return true;
    }

    private Map<String, Object> listEntry(AppManifest manifest) {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("provider", manifest.provider());
        app.put("repo", manifest.repo());
        app.put("version", manifest.version());
        app.put("installed_at", manifest.installedAt());
        app.put("updated_at", manifest.updatedAt());
        app.put("install_path",
            manifest.install() == null ? null : manifest.install().path());
        app.put("install_state", platformHandler.installState(manifest)
            .name().toLowerCase(Locale.ROOT));
        app.put("asset", manifest.asset() == null ? null : manifest.asset().name());
        return app;
    }

    private Map<String, Object> infoEntry(AppManifest manifest) {
        Map<String, Object> info = listEntry(manifest);
        info.put("release_id", manifest.releaseId());

        Map<String, Object> asset = new LinkedHashMap<>();
        if (manifest.asset() != null) {
            asset.put("name", manifest.asset().name());
            asset.put("url", manifest.asset().url());
            asset.put("sha256", manifest.asset().sha256());
        }
        info.put("asset", asset);

        if (manifest.install() != null) {
            info.put("install_root", manifest.install().root());
            AppManifest.Uninstall uninstall = manifest.install().uninstall();
            info.put("launcher", uninstall == null ? null : uninstall.path());
            info.put("product_code", uninstall == null ? null : uninstall.productCode());
        }

        Map<String, Object> verification = new LinkedHashMap<>();
        if (manifest.verification() != null) {
            verification.put("status", manifest.verification().status());
            verification.put("method", manifest.verification().method());
            verification.put("checksum_asset", manifest.verification().checksumAsset());
            verification.put("expected", manifest.verification().expected());
            verification.put("actual", manifest.verification().actual());
        }
        info.put("verification", verification);

        Map<String, Object> signature = new LinkedHashMap<>();
        if (manifest.signature() != null) {
            signature.put("status", manifest.signature().status());
            signature.put("mechanism", manifest.signature().mechanism());
            signature.put("signer", manifest.signature().signer());
            signature.put("identity", manifest.signature().identity());
            signature.put("notarized", manifest.signature().notarized());
        }
        info.put("signature", signature);

        List<Map<String, Object>> backups = new ArrayList<>();
        if (manifest.backups() != null) {
            for (AppManifest.Backup backup : manifest.backups()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("version", backup.version());
                entry.put("created_at", backup.createdAt());
                entry.put("path", backup.path());
                entry.put("artifact", backup.artifact());
                backups.add(entry);
            }
        }
        info.put("backups", backups);
        return info;
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
            printJson(infoEntry(manifest));
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
        System.out.println("Checksum: " + describeVerification(manifest.verification()));
        System.out.println("Signature: " + describePinnedSignature(manifest.signature()));
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

    private static String describeVerification(AppManifest.Verification verification) {
        if (verification == null) {
            return "not recorded";
        }
        StringBuilder value = new StringBuilder(verification.status());
        if (verification.method() != null) {
            value.append(" (").append(verification.method());
            if (verification.checksumAsset() != null) {
                value.append(", ").append(verification.checksumAsset());
            }
            value.append(")");
        }
        return value.toString();
    }

    private static String describePinnedSignature(AppManifest.Signature signature) {
        if (signature == null) {
            return "not recorded";
        }
        if (signature.isVerified()) {
            String value = "verified (" + signature.mechanism() + ") by " + describe(signature);
            if (signature.notarized() != null) {
                value += signature.notarized() ? ", notarized" : ", not notarized";
            }
            return value;
        }
        return signature.status()
            + (signature.mechanism() == null ? "" : " (" + signature.mechanism() + ")");
    }

    private boolean rollback(Command command) {
        RepoRef ref = parseRepo(command.argument);
        if (ref == null) {
            return false;
        }

        AppManifest current;
        try {
            var found = stateStore.findManifest(ref);
            if (found.isEmpty()) {
                System.err.println("Release " + ref.display() + " is not found.");
                return false;
            }
            current = found.get();
        } catch (IOException e) {
            System.err.println("Failed to load application state: " + e.getMessage());
            return false;
        }

        if (os == Platform.OS.WINDOWS
            && !platformHandler.supportsInstallerRollback(current)) {
            System.err.println("Rollback on Windows requires an MSI with a cached installer.");
            return false;
        }

        var latestBackup = backupManager.latest(current);
        if (latestBackup.isEmpty()) {
            System.err.println("No backups are available for " + ref.display() + ".");
            return false;
        }
        AppManifest.Backup backup = latestBackup.get();
        if (cmdParser.isDryRun()) {
            if (cmdParser.isJson()) {
                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("dry_run", true);
                plan.put("action", "rollback");
                plan.put("provider", ref.provider());
                plan.put("repo", ref.path());
                plan.put("from", current.version());
                plan.put("to", backup.version());
                plan.put("backup", backup.artifact());
                printJson(plan);
            } else {
                System.out.println("Dry run: would roll back " + ref.display() + " from "
                    + (current.version() == null ? "unknown" : current.version())
                    + " to " + (backup.version() == null ? "unknown" : backup.version()) + ".");
                System.out.println("  Backup: " + backup.artifact());
                System.out.println("No changes were made.");
            }
            return true;
        }

        if (os == Platform.OS.WINDOWS) {
            return rollbackWindows(current, backup, ref);
        }

        String message = "Roll back " + ref.display() + " from "
            + (current.version() == null ? "unknown" : current.version())
            + " to " + (backup.version() == null ? "unknown" : backup.version())
            + "?\nThis will replace:\n  " + current.install().path()
            + "\nwith the backup at:\n  " + backup.artifact();
        if (!confirm(message)) {
            System.err.println("Rollback declined.");
            return false;
        }

        AppManifest restored;
        Path restoreTarget;
        try {
            restored = backupManager.readManifest(backup);
            if (restored == null || !restored.isValid()) {
                System.err.println("Backup manifest is invalid.");
                return false;
            }
            restoreTarget = backup.installPath() == null || backup.installPath().isBlank()
                ? Path.of(current.install().path())
                : Path.of(backup.installPath());
            backupManager.restore(backup, restoreTarget);
            platformHandler.activateRestored(restored, restoreTarget);
        } catch (IOException e) {
            System.err.println("Rollback failed: " + e.getMessage());
            return false;
        }

        AppManifest rolledBack = rolledBackManifest(restored, current);
        if (!recordManifest(rolledBack)) {
            return false;
        }
        printRollbackResult(ref, backup);
        return true;
    }

    private boolean rollbackWindows(
        AppManifest current, AppManifest.Backup backup, RepoRef ref) {
        AppManifest restored;
        try {
            restored = backupManager.readManifest(backup);
        } catch (IOException e) {
            System.err.println("Rollback failed: " + e.getMessage());
            return false;
        }
        if (restored == null || !restored.isValid()) {
            System.err.println("Backup manifest is invalid.");
            return false;
        }
        if (!platformHandler.rollbackInstaller(
            current, restored, Path.of(backup.artifact()))) {
            System.err.println("Rollback failed; " + ref.display()
                + " may need to be reinstalled.");
            return false;
        }
        if (!recordManifest(rolledBackManifest(restored, current))) {
            return false;
        }
        printRollbackResult(ref, backup);
        return true;
    }

    private void printRollbackResult(RepoRef ref, AppManifest.Backup backup) {
        if (cmdParser.isJson()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider", ref.provider());
            result.put("repo", ref.path());
            result.put("version", backup.version());
            printJson(Map.of("rolled_back", result));
        } else {
            Output.info("Rolled back " + ref.display() + " to "
                + (backup.version() == null ? "unknown" : backup.version()) + ".");
        }
    }

    private static AppManifest rolledBackManifest(
        AppManifest restored, AppManifest current) {
        return new AppManifest(
            restored.kind(),
            restored.schemaVersion(),
            restored.provider(),
            restored.repo(),
            restored.version(),
            restored.releaseId(),
            restored.asset(),
            restored.install(),
            restored.verification(),
            current.backups() == null ? List.of() : current.backups(),
            restored.installedAt(),
            Instant.now(),
            restored.signature(),
            restored.providerType(),
            restored.providerApi());
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
        boolean healthy = true;
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Path root = stateStore.paths().root();
        Path configFile = stateStore.paths().configFile();
        Path indexFile = stateStore.paths().indexFile();

        if (!Files.exists(configFile)) {
            issues.add("Config: missing at " + configFile);
            healthy = false;
        } else {
            try {
                toolConfig = stateStore.loadConfig().config();
                providers = new ProviderRegistry(toolConfig.providers());
                applyInstallRoot();
            } catch (IOException e) {
                issues.add("Config: failed to read: " + e.getMessage());
                healthy = false;
            }
        }

        List<AppManifest> manifests = List.of();
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            issues.add("Manifests: failed to read: " + e.getMessage());
            healthy = false;
        }

        Optional<GrmIndex> index;
        try {
            index = stateStore.loadIndex();
        } catch (IOException e) {
            issues.add("Index: failed to read: " + e.getMessage());
            index = Optional.empty();
            healthy = false;
        }
        if (index.isEmpty()) {
            issues.add("Index: missing or corrupt (run a stateful command to rebuild)");
            healthy = false;
        } else {
            for (StateStore.RecoveryIssue issue
                : stateStore.findRecoveryIssues(index.get(), manifests)) {
                issues.add("Recovery: " + issue.kind() + " " + issue.entry().repo());
                healthy = false;
            }
        }

        for (AppManifest manifest : manifests) {
            PlatformHandler.InstallState state = platformHandler.installState(manifest);
            if (state == PlatformHandler.InstallState.MISSING) {
                issues.add("Missing live app: " + manifest.repo()
                    + " -> " + manifest.install().path());
                healthy = false;
            } else if (state == PlatformHandler.InstallState.UNKNOWN) {
                issues.add("Unknown live app state: " + manifest.repo()
                    + " -> " + manifest.install().path());
                healthy = false;
            }
        }

        Set<String> missingProviders = new HashSet<>();
        for (AppManifest manifest : manifests) {
            if (manifest.provider() != null
                && providers.get(manifest.provider()).isEmpty()) {
                missingProviders.add(manifest.provider());
            }
        }
        for (String provider : missingProviders) {
            issues.add("Provider not configured: " + provider
                + " (run providers recover " + provider + ")");
            healthy = false;
        }

        List<Map<String, Object>> tools = new ArrayList<>();
        for (SignatureVerifier.Tool tool : signatureVerifier.tools()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("required", tool.required());
            entry.put("available", tool.available());
            tools.add(entry);
            if (!tool.available()) {
                issues.add((tool.required() ? "Signature tool missing: " : "Optional signature tool missing: ")
                    + tool.name());
                if (tool.required()) {
                    healthy = false;
                }
            }
        }

        List<Map<String, Object>> pins = new ArrayList<>();
        for (AppManifest manifest : manifests) {
            if (manifest.signature() != null && manifest.signature().isVerified()) {
                Map<String, Object> pin = new LinkedHashMap<>();
                pin.put("repo", manifest.repo());
                pin.put("signer", manifest.signature().signer());
                pin.put("identity", manifest.signature().identity());
                pins.add(pin);
            }
        }

        Path mountPoint = platformHandler.mountPoint();
        if (mountPoint != null && Files.isDirectory(mountPoint)) {
            try (var entries = Files.list(mountPoint)) {
                if (entries.findAny().isPresent()) {
                    issues.add("Stale mount: " + mountPoint + " is not empty");
                    healthy = false;
                }
            } catch (IOException e) {
                issues.add("Stale mount: cannot read " + mountPoint + ": " + e.getMessage());
                healthy = false;
            }
        }

        Path installRoot = platformHandler.getInstallRoot();
        long freeBytes = DiskSpace.usableSpace(installRoot == null ? root : installRoot);
        if (!DiskSpace.hasAtLeast(installRoot, MIN_FREE_BYTES)) {
            issues.add("Low disk space at " + installRoot + ": "
                + (freeBytes < 0 ? "unknown" : freeBytes / (1024 * 1024) + " MB")
                + " free, need " + MIN_FREE_BYTES / (1024 * 1024) + " MB");
            healthy = false;
        }
        if (os == Platform.OS.LINUX && !manifests.isEmpty()
            && installRoot != null && !PathEnvironment.isOnPath(installRoot)) {
            warnings.add("PATH: " + installRoot + " is not on PATH");
        }

        if (os == Platform.OS.LINUX) {
            List<String> orphans = orphanCandidates();
            if (!orphans.isEmpty()) {
                issues.addAll(orphans);
                healthy = false;
            }
        }

        if (cmdParser.isJson()) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("healthy", healthy);
            report.put("state_root", root.toString());
            report.put("config", configFile.toString());
            report.put("index", indexFile.toString());
            report.put("manifests", manifests.size());
            report.put("issues", issues);
            report.put("warnings", warnings);
            report.put("signature_tools", tools);
            report.put("pins", pins);
            report.put("free_bytes", freeBytes);
            printJson(report);
        } else {
            System.out.println("State root: " + root);
            System.out.println("Config: " + configFile);
            System.out.println("Index: " + indexFile);
            System.out.println("Manifests: " + manifests.size());
            System.out.println("Free space: "
                + (freeBytes < 0 ? "unknown" : freeBytes / (1024 * 1024) + " MB"));
            for (Map<String, Object> tool : tools) {
                System.out.println("Signature tool: " + tool.get("name") + " "
                    + (Boolean.TRUE.equals(tool.get("available")) ? "available" : "missing"));
            }
            for (Map<String, Object> pin : pins) {
                System.out.println("Pinned signer: " + pin.get("repo") + " -> "
                    + pin.get("signer") + " (" + pin.get("identity") + ")");
            }
            for (String warning : warnings) {
                System.out.println("Warning: " + warning);
            }
            for (String issue : issues) {
                System.out.println(issue);
            }
        }
        return healthy;
    }

    private List<String> orphanCandidates() {
        Path liveRoot = platformHandler.getInstallRoot();
        if (liveRoot == null || !Files.isDirectory(liveRoot)) {
            return List.of();
        }
        List<String> orphans = new ArrayList<>();
        try (var files = Files.list(liveRoot)) {
            List<AppManifest> manifests = stateStore.scanManifests();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                boolean tracked = manifests.stream().anyMatch(manifest ->
                    AppPaths.repoDirectoryName(manifest.repo()).equals(name));
                if (tracked) {
                    continue;
                }
                String repo = name.replace("__", "/");
                orphans.add("Orphan candidate: " + file + " (repo: " + repo + ")");
            }
        } catch (IOException e) {
            return List.of("Failed to scan " + liveRoot + ": " + e.getMessage());
        }
        return orphans;
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
        Path live = liveRoot.resolve(AppPaths.repoDirectoryName(ref.path()));
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
                    name, live.toUri().toString(), Files.size(live), null),
                new AppManifest.Install(
                    installKind(name),
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
                printJson(Map.of("adopted", listEntry(manifest)));
            } else {
                Output.info("Adopted " + ref.display() + " at " + live + ".");
            }
            return true;
        } catch (IOException e) {
            System.err.println("Failed to adopt " + ref.display() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Prints usage help text.
     */
    private void help() {
        printUsage(System.out);
    }

    /**
     * Prints an unknown-command message and usage text, and returns the usage exit code.
     * @param command the invalid command
     * @return the usage exit code
     */
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
