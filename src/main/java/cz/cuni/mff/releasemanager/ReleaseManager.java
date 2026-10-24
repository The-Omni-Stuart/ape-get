package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
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
import cz.cuni.mff.releasemanager.verify.AssetVerifier;
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
    private final StateStore stateStore;
    private final BackupManager backupManager;
    private final Prompt prompt;
    private Platform.OS os = Platform.detectPlatform().getOS();
    private GrmConfig toolConfig = GrmConfig.defaults();

    private ReleaseManager() {
        cmdParser = new CmdParser();
        githubClient = new GithubClient();
        platformHandler = Platform.getPlatformHandler();
        verifier = new AssetVerifier(githubClient);
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
        if (command.argument == null || command.argument.split("/").length != 2) {
            System.err.println("Please specify the correct repository name of format 'owner/repo'.");
            return false;
        }
        var release = githubClient.getLatestRelease(command.argument);
        if (release.installable().isEmpty()) {
            System.err.println("Failed to retrieve the latest release.");
            return false;
        }
        Asset asset = getSingleAsset(release.installable());
        Path downloadedAsset = download(asset);
        if (downloadedAsset == null) {
            return false;
        }
        VerificationResult verification = verify(asset, release.all(), downloadedAsset);
        if (!isAllowed(verification, downloadedAsset)) {
            return false;
        }
        Path installedAsset = githubClient.installDownloadedAsset(
            downloadedAsset, command.argument);
        if (installedAsset == null) {
            System.err.println("Installation failed.");
            return false;
        }
        AppManifest manifest = createManifest(
            command.argument, release, asset, installedAsset, verification,
            Instant.now(), List.of());
        if (!recordManifest(manifest)) {
            return false;
        }
        System.out.println("Installation successful.");
        return true;
    }
    private Path download(Asset asset) {
        try {
            return githubClient.downloadAsset(asset);
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

    private AppManifest createManifest(
        String repo,
        GithubClient.ReleaseAssets release,
        Asset asset,
        Path installed,
        VerificationResult verification,
        Instant installedAt,
        List<AppManifest.Backup> backups
    ) {
        return new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            "github",
            repo,
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
                stateStore.paths().appDir("github", repo).toString(),
                new AppManifest.Uninstall(installed.toString(), List.of(), null)),
            new AppManifest.Verification(
                verification.status().name().toLowerCase(Locale.ROOT),
                "sha256",
                verification.checksumAssetName(),
                verification.expected(),
                verification.actual()),
            backups,
            installedAt,
            Instant.now());
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
        if (command.argument == null || command.argument.split("/").length != 2) {
            System.err.println("Please specify the correct repository name of format 'owner/repo'.");
            return false;
        }

        AppManifest manifest;
        try {
            var found = stateStore.findManifest(command.argument);
            if (found.isEmpty()) {
                System.err.println("Release " + command.argument + " is not found.");
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
        if (!platformHandler.uninstall(Path.of(uninstallPath))) {
            System.err.println("Failed to uninstall " + manifest.repo() + ".");
            return false;
        }

        try {
            if (!stateStore.removeManifest(manifest.repo())) {
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
        AppManifest previous;
        try {
            var found = stateStore.findManifest(command.argument);
            if (found.isEmpty()) {
                System.err.println("Release " + command.argument + " is not found.");
                return false;
            }
            previous = found.get();
        } catch (IOException e) {
            System.err.println("Failed to load application state: " + e.getMessage());
            return false;
        }

        var latest = githubClient.getLatestRelease(command.argument);
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

        Path downloadedAsset = download(newAsset);
        if (downloadedAsset == null) {
            return false;
        }
        VerificationResult verification = verify(newAsset, latest.all(), downloadedAsset);
        if (!isAllowed(verification, downloadedAsset)) {
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
                githubClient.discardAsset(downloadedAsset);
                return false;
            }
        }

        Path installedAsset = githubClient.installDownloadedAsset(
            downloadedAsset, command.argument);
        if (installedAsset == null) {
            System.err.println("Installation failed.");
            return false;
        }

        AppManifest updated = createManifest(
            command.argument,
            latest,
            newAsset,
            installedAsset,
            verification,
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
            System.out.println(manifest.repo() + " " + version);
        }
        return true;
    }

    private boolean rollback(Command command) {
        if (os == Platform.OS.WINDOWS) {
            System.err.println("rollback is not supported for Windows installers yet.");
            return false;
        }
        if (command.argument == null || command.argument.split("/").length != 2) {
            System.err.println("Please specify the correct repository name of format 'owner/repo'.");
            return false;
        }

        AppManifest current;
        try {
            var found = stateStore.findManifest(command.argument);
            if (found.isEmpty()) {
                System.err.println("Release " + command.argument + " is not found.");
                return false;
            }
            current = found.get();
        } catch (IOException e) {
            System.err.println("Failed to load application state: " + e.getMessage());
            return false;
        }

        var latestBackup = backupManager.latest(current);
        if (latestBackup.isEmpty()) {
            System.err.println("No backups are available for " + command.argument + ".");
            return false;
        }
        AppManifest.Backup backup = latestBackup.get();

        String message = "Roll back " + current.repo() + " from "
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
            Instant.now());
        if (!recordManifest(rolledBack)) {
            return false;
        }
        System.out.println("Rolled back " + command.argument + " to "
            + (backup.version() == null ? "unknown" : backup.version()) + ".");
        return true;
    }

    private boolean doctor() {
        System.out.println("State root: " + stateStore.paths().root());
        System.out.println("Config: " + stateStore.paths().configFile());
        System.out.println("Index: " + stateStore.paths().indexFile());

        boolean healthy = true;
        if (!Files.exists(stateStore.paths().configFile())) {
            System.out.println("Config: missing");
            healthy = false;
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
            String installPath = manifest.install().path();
            if (installPath == null || !Files.exists(Path.of(installPath))) {
                System.out.println("Missing live app: " + manifest.repo() + " -> " + installPath);
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
        if (command.argument == null || command.argument.split("/").length != 2) {
            System.err.println("Please specify the correct repository name of format 'owner/repo'.");
            return false;
        }
        Path liveRoot = platformHandler.getInstallRoot();
        if (liveRoot == null) {
            System.err.println("Cannot determine the install root.");
            return false;
        }
        Path live = liveRoot.resolve(AppPaths.repoDirectoryName(command.argument));
        if (!Files.isRegularFile(live)) {
            System.err.println("No installed file found at " + live + ".");
            return false;
        }
        try {
            if (stateStore.findManifest(command.argument).isPresent()) {
                System.err.println("Release " + command.argument + " is already tracked.");
                return false;
            }
        } catch (IOException e) {
            System.err.println("Failed to read application state: " + e.getMessage());
            return false;
        }

        String message = "Adopt " + live + " as " + command.argument
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
                "github",
                command.argument,
                null,
                null,
                new AppManifest.Asset(
                    name, live.toUri().toString(), Files.size(live), null),
                new AppManifest.Install(
                    installKind(name),
                    live.toString(),
                    stateStore.paths().appDir("github", command.argument).toString(),
                    new AppManifest.Uninstall(live.toString(), List.of(), null)),
                new AppManifest.Verification("unknown", "none", null, null, null),
                List.of(),
                Files.getLastModifiedTime(live).toInstant(),
                Instant.now());
            if (!recordManifest(manifest)) {
                return false;
            }
            System.out.println("Adopted " + command.argument + " at " + live + ".");
            return true;
        } catch (IOException e) {
            System.err.println("Failed to adopt " + command.argument + ": " + e.getMessage());
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
        out.println("  rollback [name] - restore the latest backup");
        out.println("  doctor - diagnose state and orphaned installs");
        out.println("  adopt [name] - adopt an existing Linux install");
        out.println("  help - display this help message");
    }
}
