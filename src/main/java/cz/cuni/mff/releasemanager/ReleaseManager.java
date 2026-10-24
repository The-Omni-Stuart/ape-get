package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.io.PrintStream;
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
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmIndex;
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
    private final Prompt prompt;

    private ReleaseManager() {
        cmdParser = new CmdParser();
        githubClient = new GithubClient();
        platformHandler = Platform.getPlatformHandler();
        verifier = new AssetVerifier(githubClient);
        stateStore = new StateStore(AppPaths.forCurrentPlatform());
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
            || command == Command.LIST;
        if (stateful && !ensureState()) {
            return 1;
        }

        return switch (command) {
            case SEARCH -> search(command) ? 0 : 1;
            case INSTALL -> install(command) ? 0 : 1;
            case UNINSTALL -> uninstall(command) ? 0 : 1;
            case UPDATE -> update(command) ? 0 : 1;
            case LIST -> list() ? 0 : 1;
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
        boolean strict = cmdParser.isStrict();
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
        String name = asset.name().toLowerCase(Locale.ROOT);
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
            previous.backups() == null ? List.of() : previous.backups());
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
        out.println("  help - display this help message");
    }
}
