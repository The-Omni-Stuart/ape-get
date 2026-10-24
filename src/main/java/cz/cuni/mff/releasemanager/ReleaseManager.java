package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.ReleaseInfo;
import cz.cuni.mff.releasemanager.types.ReleasesList;
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

    private ReleaseManager() {
        cmdParser = new CmdParser();
        githubClient = new GithubClient();
        platformHandler = Platform.getPlatformHandler();
        verifier = new AssetVerifier(githubClient);
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
        if (!isAllowed(verify(asset, release.all(), downloadedAsset), downloadedAsset)) {
            return false;
        }
        Path installedAsset = githubClient.installDownloadedAsset(
            downloadedAsset, command.argument);
        if (installedAsset == null) {
            System.err.println("Installation failed.");
            return false;
        }
        if (!addReleaseToList(command.argument, installedAsset, asset)) {
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
     * Adds a release to the list of installed releases.
     *
     * @param repoFullName the full name of the repository (e.g., "owner/repo")
     * @param installedRelease the path to the installed asset
     * @param asset the asset
     * @return true if the release was recorded
     */
    private boolean addReleaseToList(String repoFullName, Path installedRelease, Asset asset) {
        ReleaseInfo release = new ReleaseInfo(
            repoFullName,
            Instant.now(),
            installedRelease.toString(),
            asset
        );
        try {
            platformHandler.addReleaseToList(release);
            return true;
        }
        catch (IOException e) {
            System.err.println("Failed to add release to list: " + e.getMessage());
            return false;
        }
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
        ReleasesList releasesList;
        try {
            releasesList = platformHandler.loadReleasesList();
        } catch (IOException e) {
            System.err.println("Failed to find installed release.");
            return false;
        }
        if (releasesList == null) {
            System.err.println("No releases installed.");
            return false;
        }
        var found = releasesList.releases().stream()
            .filter(release -> release.repo().equals(command.argument))
            .findFirst();
        if (found.isEmpty()) {
            System.err.println("Release " + command.argument + " is not found.");
            return false;
        }
        ReleaseInfo release = found.get();
        if (!platformHandler.uninstall(Path.of(release.uninstallPath()))) {
            System.err.println("Failed to uninstall " + release.repo() + ".");
            return false;
        }
        if (!platformHandler.removeReleaseFromList(release)) {
            System.err.println(
                "Uninstalled " + release.repo() + ", but failed to update the release list.");
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
        ReleasesList releasesList;
        try {
            releasesList = platformHandler.loadReleasesList();
        } catch (IOException e) {
            System.err.println("Failed to find installed release.");
            return false;
        }
        if (releasesList == null) {
            System.err.println("No releases installed.");
            return false;
        }
        var found = releasesList.releases().stream()
            .filter(release -> release.repo().equals(command.argument))
            .findFirst();
        if (found.isEmpty()) {
            System.err.println("Release " + command.argument + " is not found.");
            return false;
        }
        ReleaseInfo release = found.get();
        var latest = githubClient.getLatestRelease(command.argument);
        List<Asset> assets = latest.installable();
        if (assets.isEmpty()) {
            System.err.println("No asset found.");
            return false;
        }
        Asset newAsset = null;
        for (Asset asset : assets) {
            if (asset.name().equals(release.asset().name())) {
                newAsset = asset;
                break;
            }
        }
        if (newAsset == null) {
            newAsset = getSingleAsset(assets);
        }
        if (release.asset().url().equals(newAsset.url())) {
            System.out.println("Already up to date.");
            return true;
        }
        Path downloadedAsset = download(newAsset);
        if (downloadedAsset == null) {
            return false;
        }
        if (!isAllowed(verify(newAsset, latest.all(), downloadedAsset), downloadedAsset)) {
            return false;
        }
        if (!platformHandler.uninstall(Path.of(release.uninstallPath()))) {
            System.err.println("Failed to uninstall the old version; update aborted.");
            githubClient.discardAsset(downloadedAsset);
            return false;
        }
        Path installedAsset = githubClient.installDownloadedAsset(
            downloadedAsset, command.argument);
        if (installedAsset == null) {
            System.err.println("Installation failed.");
            return false;
        }
        if (!addReleaseToList(command.argument, installedAsset, newAsset)) {
            return false;
        }
        System.out.println("Successfully updated.");
        return true;
    }

    /**
     * Lists all installed releases.
     */
    private boolean list() {
        ReleasesList releasesList;
        try {
            releasesList = platformHandler.loadReleasesList();
        } catch (IOException e) {
            System.err.println("Failed to load releases list.");
            return false;
        }
        if (releasesList == null) {
            System.out.println("No releases installed.");
            return true;
        }
        List<ReleaseInfo> releases = releasesList.releases();
        for (ReleaseInfo release : releases) {
            System.out.println(release.repo());
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
