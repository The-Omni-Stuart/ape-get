package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.BackupManager;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.verify.AssetVerifier;
import cz.cuni.mff.releasemanager.verify.SignatureResult;
import cz.cuni.mff.releasemanager.verify.SignatureVerifier;
import cz.cuni.mff.releasemanager.verify.TrustGate;
import cz.cuni.mff.releasemanager.verify.VerificationResult;
import cz.cuni.mff.releasemanager.verify.VerificationStatus;

// install/update: pick asset, download, verify, back up, record
public final class InstallService {

    private final Platform.OS os;
    private final CmdParser cmdParser;
    private final GrmConfig toolConfig;
    private final PlatformHandler platformHandler;
    private final AssetVerifier verifier;
    private final SignatureVerifier signatureVerifier;
    private final BackupManager backupManager;
    private final StateStore stateStore;
    private final Function<String, RepoRef> parseRepo;
    private final Function<String, ReleaseProvider> resolveProvider;
    private final Function<AppManifest, Boolean> recordManifest;
    private final Consumer<Path> discardAsset;
    private final Predicate<String> confirm;
    private final Consumer<Object> printJson;

    public InstallService(
        Platform.OS os,
        CmdParser cmdParser,
        GrmConfig toolConfig,
        PlatformHandler platformHandler,
        AssetVerifier verifier,
        SignatureVerifier signatureVerifier,
        BackupManager backupManager,
        StateStore stateStore,
        Function<String, RepoRef> parseRepo,
        Function<String, ReleaseProvider> resolveProvider,
        Function<AppManifest, Boolean> recordManifest,
        Consumer<Path> discardAsset,
        Predicate<String> confirm,
        Consumer<Object> printJson) {
        this.os = os;
        this.cmdParser = cmdParser;
        this.toolConfig = toolConfig;
        this.platformHandler = platformHandler;
        this.verifier = verifier;
        this.signatureVerifier = signatureVerifier;
        this.backupManager = backupManager;
        this.stateStore = stateStore;
        this.parseRepo = parseRepo;
        this.resolveProvider = resolveProvider;
        this.recordManifest = recordManifest;
        this.discardAsset = discardAsset;
        this.confirm = confirm;
        this.printJson = printJson;
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


    public boolean install(Command command) {
        RepoRef ref = parseRepo.apply(command.argument);
        if (ref == null) {
            return false;
        }
        ReleaseProvider provider = resolveProvider.apply(ref.provider());
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
        if (previous != null) {
            System.err.println(ref.display() + " is already installed; use update.");
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
        TrustGate trust = new TrustGate(
            cmdParser, toolConfig, verifier, signatureVerifier,
            discardAsset, confirm);
        VerificationResult verification = trust.verify(
            provider, asset, release.all(), downloadedAsset);
        if (!trust.isAllowed(verification, downloadedAsset)) {
            return false;
        }
        SignatureResult signature = trust.verifySignature(
            provider, asset, release.all(), downloadedAsset);
        if (!trust.isSignatureAllowed(signature, downloadedAsset, previous)) {
            return false;
        }
        if (!trust.isSignerAllowed(previous, signature, downloadedAsset, "install")) {
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
        if (!recordManifest.apply(manifest)) {
            return false;
        }
        if (cmdParser.isJson()) {
            printJson.accept(Map.of("installed",
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
        Path launcher = platformHandler.launcherPath(ref.provider(), ref.path());
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
            TrustGate.signatureRecord(signature),
            provider.type(),
            provider.resolvedApi());
    }


    private static String installKind(Asset asset) {
        return installKind(asset.name());
    }

    public static String installKind(String fileName) {
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
        Path launcher = platformHandler.launcherPath(ref.provider(), ref.path());
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
            printJson.accept(plan);
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
        Path launcher = platformHandler.launcherPath(ref.provider(), ref.path());
        result.put("launcher", launcher == null ? null : launcher.toString());
        return result;
    }


    private static String versionLabel(ReleaseAssets release) {
        return release.version() == null ? "(unknown version)" : release.version();
    }


    private Asset selectAsset(List<Asset> assets) {
        String requested = cmdParser.getAsset();
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


    private Asset getSingleAsset(List<Asset> assets) {
        if (assets.size() == 1) {
            return assets.get(0);
        }
        if (cmdParser.isJson() || System.console() == null) {
            System.err.println("Multiple assets found; choose one with --asset:");
            for (int i = 0; i < assets.size(); i++) {
                System.err.println("  " + (i + 1) + ": " + assets.get(i).name());
            }
            return null;
        }
        System.out.println("Multiple assets found. Please select one:");
        for (int i = 0; i < assets.size(); i++) {
            System.out.println((i + 1) + ": " + assets.get(i).name());
        }
        int choice = getChoice(1, assets.size());
        return assets.get(choice - 1);
    }


    private int getChoice(int min, int max) {
        int input = cmdParser.getUserInput();
        if (input < min || input > max) {
            System.err.println("Invalid choice. Valid options are: " + min + " - " + max);
            return getChoice(min, max);
        }
        return input;
    }


    public boolean update(Command command) {
        RepoRef ref = parseRepo.apply(command.argument);
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
        ReleaseProvider provider = resolveProvider.apply(previous.provider());
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
        TrustGate trust = new TrustGate(
            cmdParser, toolConfig, verifier, signatureVerifier,
            discardAsset, confirm);
        VerificationResult verification = trust.verify(
            provider, newAsset, latest.all(), downloadedAsset);
        if (!trust.isAllowed(verification, downloadedAsset)) {
            return false;
        }
        SignatureResult signature = trust.verifySignature(
            provider, newAsset, latest.all(), downloadedAsset);
        if (!trust.isSignatureAllowed(signature, downloadedAsset, previous)) {
            return false;
        }
        if (!trust.isSignerAllowed(previous, signature, downloadedAsset, "update")) {
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
        if (!recordManifest.apply(updated)) {
            return false;
        }
        if (cmdParser.isJson()) {
            printJson.accept(Map.of("updated",
                releaseResult(ref, latest, newAsset, installedAsset)));
        } else {
            Output.info("Successfully updated.");
        }
        return true;
    }
}
