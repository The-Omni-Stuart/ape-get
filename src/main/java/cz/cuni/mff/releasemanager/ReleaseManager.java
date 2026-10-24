package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.cmd.ConsolePrompt;
import cz.cuni.mff.releasemanager.cmd.Prompt;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.GithubProvider;
import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
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

    private ReleaseManager() {
        cmdParser = new CmdParser();
        githubClient = new GithubClient();
        platformHandler = Platform.getPlatformHandler();
        verifier = new AssetVerifier(githubClient);
        signatureVerifier = SignatureVerifiers.forCurrentPlatform(githubClient);
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

        boolean stateful = command == Command.INSTALL
            || command == Command.UPDATE
            || command == Command.UNINSTALL
            || command == Command.LIST
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
                System.out.println("Rebuilt index at " + stateStore.paths().indexFile());
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
                System.out.println("Restored manifest for " + issue.entry().repo() + ".");
                changed = true;
            } else {
                if (!confirm(dropMessage(issue.entry()))) {
                    System.err.println("Stale index entry kept for " + issue.entry().repo() + ".");
                    return false;
                }
                System.out.println("Removed stale index entry for " + issue.entry().repo() + ".");
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
        System.out.println("First-time setup complete.");
        System.out.println("  config: " + stateStore.paths().configFile());
        System.out.println("  index:  " + stateStore.paths().indexFile());
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
                    : new GithubClient(config.resolvedApi()),
                config);
            case GITEA, GITLAB -> {
                System.err.println(
                    "Provider type '" + config.type() + "' is not implemented yet.");
                yield null;
            }
        };
    }

    private ReleaseAssets latest(ReleaseProvider provider, String path) {
        try {
            return provider.latest(path);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("Failed to read the latest release: " + e.getMessage());
            return null;
        }
    }

    private Path installDownloaded(
        ReleaseProvider provider, Path downloaded, String repoPath) {
        Path installed = null;
        try {
            installed = platformHandler.install(downloaded, repoPath);
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
        var searchResult = githubClient.searchRepoByName(command.argument);
        if (searchResult.isEmpty()) {
            return false;
        }
        var items = searchResult.get().items();
        if (items.isEmpty()) {
            System.out.println("No results found.");
            return true;
        }
        System.out.println("Found " + items.size() + " repositories.");
        for (Repo repo : items) {
            System.out.println(repo.fullName() + ": " + repo.description());
        }
        System.out.println("To install a release, use the 'install' command followed by the repository name.");
        return true;
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
            System.err.println("Failed to retrieve the latest release.");
            return false;
        }
        Asset asset = getSingleAsset(release.installable());
        Path downloadedAsset = download(provider, asset);
        if (downloadedAsset == null) {
            return false;
        }
        VerificationResult verification = verify(asset, release.all(), downloadedAsset);
        if (!isAllowed(verification, downloadedAsset)) {
            return false;
        }
        SignatureResult signature = verifySignature(
            asset, release.all(), downloadedAsset);
        if (!isSignatureAllowed(signature, downloadedAsset, previous)) {
            return false;
        }
        if (!isSignerAllowed(previous, signature, downloadedAsset, "install")) {
            return false;
        }
        Path installedAsset = installDownloaded(provider, downloadedAsset, ref.path());
        if (installedAsset == null) {
            System.err.println("Installation failed.");
            return false;
        }
        AppManifest manifest = createManifest(
            ref, release, asset, installedAsset, verification,
            signature, Instant.now(), List.of());
        if (!recordManifest(manifest)) {
            return false;
        }
        System.out.println("Installation successful.");
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

    private VerificationResult verify(Asset asset, List<Asset> releaseAssets, Path downloaded) {
        try {
            return verifier.verify(asset, releaseAssets, downloaded);
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
        Asset asset, List<Asset> releaseAssets, Path downloaded) {
        try {
            return signatureVerifier.verify(asset, releaseAssets, downloaded);
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
            System.out.println(signature.message());
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
        ReleaseAssets release,
        Asset asset,
        Path installed,
        VerificationResult verification,
        SignatureResult signature,
        Instant installedAt,
        List<AppManifest.Backup> backups
    ) {
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
                    installed.toString(), List.of(), platformHandler.productCode(installed))),
            new AppManifest.Verification(
                verification.status().name().toLowerCase(Locale.ROOT),
                "sha256",
                verification.checksumAssetName(),
                verification.expected(),
                verification.actual()),
            backups,
            installedAt,
            Instant.now(),
            signatureRecord(signature));
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
            if (!stateStore.removeManifest(ref)) {
                System.err.println(
                    "Uninstalled " + manifest.repo() + ", but failed to remove its manifest.");
                return false;
            }
            stateStore.rebuildIndex();
        } catch (IOException e) {
            System.err.println(
                "Uninstalled " + manifest.repo() + ", but failed to update state: "
                    + e.getMessage());
            return false;
        }

        System.out.println("Successfully uninstalled.");
        return true;
    }

    /**
     * Updates an installed release if a newer version is available.
     *
     * @param command the command containing the repository name
     */
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
        for (Asset asset : assets) {
            if (asset.name().equals(previous.asset().name())) {
                newAsset = asset;
                break;
            }
        }
        if (newAsset == null) {
            newAsset = getSingleAsset(assets);
        }
        if (previous.asset().url().equals(newAsset.url())) {
            System.out.println("Already up to date.");
            return true;
        }

        Path downloadedAsset = download(provider, newAsset);
        if (downloadedAsset == null) {
            return false;
        }
        VerificationResult verification = verify(newAsset, latest.all(), downloadedAsset);
        if (!isAllowed(verification, downloadedAsset)) {
            return false;
        }
        SignatureResult signature = verifySignature(
            newAsset, latest.all(), downloadedAsset);
        if (!isSignatureAllowed(signature, downloadedAsset, previous)) {
            return false;
        }
        if (!isSignerAllowed(previous, signature, downloadedAsset, "update")) {
            return false;
        }

        List<AppManifest.Backup> backups = previous.backups() == null
            ? List.of()
            : previous.backups();
        if (toolConfig.backupCount() > 0
            && os != Platform.OS.WINDOWS) {
            try {
                backups = backupManager.createAndPrune(
                    previous, toolConfig.backupCount());
            } catch (IOException e) {
                System.err.println("Failed to create backup; update aborted: " + e.getMessage());
                provider.discardAsset(downloadedAsset);
                return false;
            }
        }

        Path installedAsset = installDownloaded(provider, downloadedAsset, ref.path());
        if (installedAsset == null) {
            System.err.println("Installation failed.");
            return false;
        }

        AppManifest updated = createManifest(
            ref,
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
        System.out.println("Successfully updated.");
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
        if (manifests.isEmpty()) {
            System.out.println("No releases installed.");
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

    private boolean info(Command command) {
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
        if (os == Platform.OS.WINDOWS) {
            System.err.println("rollback is not supported for Windows installers yet.");
            return false;
        }
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

        var latestBackup = backupManager.latest(current);
        if (latestBackup.isEmpty()) {
            System.err.println("No backups are available for " + ref.display() + ".");
            return false;
        }
        AppManifest.Backup backup = latestBackup.get();

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
        try {
            restored = backupManager.readManifest(backup);
            if (restored == null || !restored.isValid()) {
                System.err.println("Backup manifest is invalid.");
                return false;
            }
            backupManager.restore(backup, Path.of(current.install().path()));
        } catch (IOException e) {
            System.err.println("Rollback failed: " + e.getMessage());
            return false;
        }

        AppManifest rolledBack = new AppManifest(
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
            restored.signature());
        if (!recordManifest(rolledBack)) {
            return false;
        }
        System.out.println("Rolled back " + ref.display() + " to "
            + (backup.version() == null ? "unknown" : backup.version()) + ".");
        return true;
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
        System.out.println("State root: " + stateStore.paths().root());
        System.out.println("Config: " + stateStore.paths().configFile());
        System.out.println("Index: " + stateStore.paths().indexFile());

        boolean healthy = true;
        if (!Files.exists(stateStore.paths().configFile())) {
            System.out.println("Config: missing");
            healthy = false;
        } else {
            try {
                toolConfig = stateStore.loadConfig().config();
                applyInstallRoot();
            } catch (IOException e) {
                System.err.println("Failed to read config: " + e.getMessage());
                healthy = false;
            }
        }

        List<AppManifest> manifests;
        try {
            manifests = stateStore.scanManifests();
        } catch (IOException e) {
            System.err.println("Failed to read manifests: " + e.getMessage());
            return false;
        }
        System.out.println("Manifests: " + manifests.size());

        Optional<GrmIndex> index;
        try {
            index = stateStore.loadIndex();
        } catch (IOException e) {
            System.err.println("Failed to read index: " + e.getMessage());
            index = Optional.empty();
            healthy = false;
        }
        if (index.isEmpty()) {
            System.out.println("Index: missing or corrupt (run a stateful command to rebuild)");
            healthy = false;
        } else {
            for (StateStore.RecoveryIssue issue
                : stateStore.findRecoveryIssues(index.get(), manifests)) {
                System.out.println("Recovery: " + issue.kind() + " " + issue.entry().repo());
                healthy = false;
            }
        }

        for (AppManifest manifest : manifests) {
            PlatformHandler.InstallState state = platformHandler.installState(manifest);
            if (state == PlatformHandler.InstallState.MISSING) {
                System.out.println("Missing live app: " + manifest.repo()
                    + " -> " + manifest.install().path());
                healthy = false;
            } else if (state == PlatformHandler.InstallState.UNKNOWN) {
                System.out.println("Unknown live app state: " + manifest.repo()
                    + " -> " + manifest.install().path());
                healthy = false;
            }
        }

        if (os == Platform.OS.LINUX) {
            healthy &= scanOrphans();
        }
        return healthy;
    }

    private boolean scanOrphans() {
        Path liveRoot = platformHandler.getInstallRoot();
        if (liveRoot == null || !Files.isDirectory(liveRoot)) {
            return true;
        }
        boolean healthy = true;
        try (var files = Files.list(liveRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String repo = repoFromInstallFile(file);
                if (repo == null || stateStore.findManifest(repo).isPresent()) {
                    continue;
                }
                System.out.println("Orphan candidate: " + file + " (repo: " + repo + ")");
                healthy = false;
            }
        } catch (IOException e) {
            System.err.println("Failed to scan " + liveRoot + ": " + e.getMessage());
            return false;
        }
        return healthy;
    }

    private static String repoFromInstallFile(Path file) {
        String name = file.getFileName().toString();
        int separator = name.indexOf("__");
        if (separator <= 0 || separator + 2 >= name.length()) {
            return null;
        }
        return name.substring(0, separator) + "/" + name.substring(separator + 2);
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
                Instant.now());
            if (!recordManifest(manifest)) {
                return false;
            }
            System.out.println("Adopted " + ref.display() + " at " + live + ".");
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
        out.println("  rollback [name] - restore the latest backup");
        out.println("  doctor - diagnose state and orphaned installs");
        out.println("  adopt [name] - adopt an existing Linux install");
        out.println("  help - display this help message");
        out.println("Options:");
        out.println("  --strict - fail when a checksum is missing or unusable");
        out.println("  --require-signature - fail when no verified signature is available");
        out.println("  --allow-signer-change - accept a changed signer after confirmation");
        out.println("  --yes - assume yes for confirmation prompts");
    }
}
