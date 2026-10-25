package cz.cuni.mff.releasemanager.install;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.platform.PackageKind;
import cz.cuni.mff.releasemanager.provider.ProviderResolver;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.provider.UpdateChannel;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ManifestStore;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.RepoParser;
import cz.cuni.mff.releasemanager.utils.StagedAsset;
import cz.cuni.mff.releasemanager.verify.SignatureResult;
import cz.cuni.mff.releasemanager.verify.TrustGate;
import cz.cuni.mff.releasemanager.verify.VerificationResult;

// install/update: fetch, choose, download, verify, back up, record
public final class InstallService {
    private final CmdParser cmdParser;
    private final ManifestStore manifests;
    private final ProviderResolver resolveProvider;
    private final InstallComponents components;
    private final PackagePlanner planner;
    private final UpdateChannel defaultChannel;

    public InstallService(
        CmdParser cmdParser,
        ManifestStore manifests,
        ProviderResolver resolveProvider,
        InstallComponents components,
        PackagePlanner planner,
        UpdateChannel defaultChannel) {
        this.cmdParser = cmdParser;
        this.manifests = manifests;
        this.resolveProvider = resolveProvider;
        this.components = components;
        this.planner = planner;
        this.defaultChannel = defaultChannel == null ? UpdateChannel.STABLE : defaultChannel;
    }

    public boolean install(Command command) {
        RepoRef ref = RepoParser.parseOrNull(command.argument);
        if (ref == null) {
            return false;
        }
        ReleaseProvider provider = resolveProvider.resolve(ref.provider());
        if (provider == null) {
            return false;
        }
        AppManifest previous;
        try {
            previous = manifests.find(ref).orElse(null);
        } catch (IOException e) {
            Output.error("Failed to load application state: " + e.getMessage());
            return false;
        }
        if (previous != null) {
            Output.error(ref.display() + " is already installed; use update.");
            return false;
        }
        UpdateChannel channel = channel();
        if (channel == null) {
            return false;
        }
        ReleaseAssets release = components.fetcher().fetch(
            provider, ref.path(), cmdParser.getVersion(), channel);
        if (release == null || release.installable().isEmpty()) {
            Output.error("Failed to retrieve the release.");
            return false;
        }
        PackageKind requested;
        try {
            requested = planner.requestedKind();
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return false;
        }
        List<Asset> candidates = planner.candidates(
            cmdParser.getAsset() == null ? release.installable() : release.all(),
            requested, cmdParser.getAsset() != null);
        if (candidates.isEmpty()) {
            Output.error("No compatible package found for this platform.");
            return false;
        }
        Asset asset = components.chooser().choose(candidates);
        if (asset == null) {
            return false;
        }
        PackagePlan plan = planner.plan(ref, asset, requested, null);
        if (plan == null) {
            return false;
        }
        if (cmdParser.isDryRun()) {
            components.reporter().printPlan("install", ref, release, plan);
            return true;
        }
        Path downloaded = components.downloader().download(provider, asset);
        if (downloaded == null) {
            return false;
        }
        TrustGate gate = components.trust().create();
        VerificationResult verification = gate.verify(
            provider, asset, release.all(), downloaded);
        if (!gate.isAllowed(verification, downloaded)) {
            return false;
        }
        SignatureResult signature = gate.verifySignature(
            provider, asset, release.all(), downloaded);
        if (!gate.isSignatureAllowed(signature, downloaded, previous)) {
            return false;
        }
        if (!gate.isSignerAllowed(previous, signature, downloaded, "install")) {
            return false;
        }
        plan = confirmPlan(ref, plan, downloaded, previous);
        if (plan == null) {
            return false;
        }
        Path installed = components.platform().install(
            downloaded, ref, release.version(),
            plan.kind(), plan.command(), cmdParser.isAssimilate(),
            cmdParser.isDesktop(), cmdParser.getIcon(), cmdParser.isTerminal());
        if (installed == null) {
            Output.error("Installation failed.");
            return false;
        }
        AppManifest manifest = components.manifestFactory().create(
            ref, provider.config(), release, asset, plan.kind(), plan.command(),
            cmdParser.isDesktop(), installed, verification, signature,
            Instant.now(), List.of());
        if (!manifests.save(manifest)) {
            return false;
        }
        components.reporter().installed(ref, release, plan, installed);
        return true;
    }

    public boolean update(Command command) {
        RepoRef ref = RepoParser.parseOrNull(command.argument);
        if (ref == null) {
            return false;
        }
        AppManifest previous;
        try {
            var found = manifests.find(ref);
            if (found.isEmpty()) {
                Output.error("Release " + ref.display() + " is not found.");
                return false;
            }
            previous = found.get();
        } catch (IOException e) {
            Output.error("Failed to load application state: " + e.getMessage());
            return false;
        }
        return updateOne(previous, cmdParser.getVersion(), true).status()
            != UpdateResult.Status.FAILED;
    }

    public boolean updateAll(boolean report) {
        List<AppManifest> installed;
        try {
            installed = manifests.scan();
        } catch (IOException e) {
            Output.error("Failed to load applications: " + e.getMessage());
            return false;
        }
        List<UpdateResult> results = new java.util.ArrayList<>();
        for (AppManifest previous : installed) {
            results.add(updateOne(previous, null, false));
        }
        if (report) {
            components.reporter().batch(results);
        }
        return results.stream()
            .noneMatch(result -> result.status() == UpdateResult.Status.FAILED);
    }

    private UpdateResult updateOne(
        AppManifest previous, String version, boolean report) {
        RepoRef ref = new RepoRef(previous.provider(), previous.repo());
        String fromVersion = previous.version();
        ReleaseProvider provider = resolveProvider.resolve(previous.provider());
        if (provider == null) {
            return failed(ref, fromVersion, null);
        }
        UpdateChannel channel = channel();
        if (channel == null) {
            return failed(ref, fromVersion, null);
        }
        ReleaseAssets latest = components.fetcher().fetch(
            provider, previous.repo(), version, channel);
        if (latest == null) {
            return failed(ref, fromVersion, null);
        }
        PackageKind requested;
        try {
            requested = planner.requestedKind();
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return failed(ref, fromVersion, null);
        }
        List<Asset> assets = planner.candidates(
            cmdParser.getAsset() == null ? latest.installable() : latest.all(),
            requested, cmdParser.getAsset() != null);
        if (assets.isEmpty()) {
            Output.error("No compatible package found for this platform.");
            return failed(ref, fromVersion, null);
        }
        Asset newAsset = components.chooser().chooseForUpdate(assets, previous);
        if (newAsset == null) {
            return failed(ref, fromVersion, null);
        }
        PackagePlan plan = planner.plan(ref, newAsset, requested, previous);
        if (plan == null) {
            return failed(ref, fromVersion, null);
        }
        if (previous.asset().url().equals(newAsset.url())) {
            if (report) {
                components.reporter().upToDate(ref, previous.version());
            }
            return new UpdateResult(
                ref.provider(), ref.path(), fromVersion, previous.version(),
                UpdateResult.Status.UP_TO_DATE, null);
        }
        if (cmdParser.isDryRun()) {
            if (report) {
                components.reporter().printPlan("update", ref, latest, plan);
            }
            return new UpdateResult(
                ref.provider(), ref.path(), fromVersion, latest.version(),
                UpdateResult.Status.PLANNED, null);
        }
        Path downloaded = components.downloader().download(provider, newAsset);
        if (downloaded == null) {
            return failed(ref, fromVersion, null);
        }
        TrustGate gate = components.trust().create();
        VerificationResult verification = gate.verify(
            provider, newAsset, latest.all(), downloaded);
        if (!gate.isAllowed(verification, downloaded)) {
            return failed(ref, fromVersion, null);
        }
        SignatureResult signature = gate.verifySignature(
            provider, newAsset, latest.all(), downloaded);
        if (!gate.isSignatureAllowed(signature, downloaded, previous)) {
            return failed(ref, fromVersion, null);
        }
        if (!gate.isSignerAllowed(previous, signature, downloaded, "update")) {
            return failed(ref, fromVersion, null);
        }
        plan = confirmPlan(ref, plan, downloaded, previous);
        if (plan == null) {
            return failed(ref, fromVersion, null);
        }
        boolean desktop = cmdParser.isDesktop()
            || previous.install().desktopEntry() != null;
        List<AppManifest.Backup> backupList;
        try {
            backupList = components.backups().forUpdate(previous);
        } catch (IOException e) {
            StagedAsset.discard(downloaded);
            return failed(ref, fromVersion,
                "Failed to create backup; update aborted: " + e.getMessage());
        }
        Path installed = components.platform().install(
            downloaded, ref, latest.version(),
            plan.kind(), plan.command(), cmdParser.isAssimilate(),
            cmdParser.isDesktop(), cmdParser.getIcon(), cmdParser.isTerminal());
        if (installed == null) {
            return failed(ref, fromVersion, "Installation failed.");
        }
        AppManifest updated = components.manifestFactory().create(
            ref, provider.config(), latest, newAsset, plan.kind(), plan.command(),
            desktop, installed, verification, signature,
            previous.installedAt(), backupList);
        if (!manifests.save(updated)) {
            return failed(ref, fromVersion, null);
        }
        if (report) {
            components.reporter().updated(ref, latest, plan, installed);
        }
        return new UpdateResult(
            ref.provider(), ref.path(), fromVersion, latest.version(),
            UpdateResult.Status.UPDATED, null);
    }

    private UpdateResult failed(RepoRef ref, String fromVersion, String message) {
        if (message != null && !message.isBlank()) {
            Output.error(message);
        }
        return new UpdateResult(
            ref.provider(), ref.path(), fromVersion, null,
            UpdateResult.Status.FAILED, message);
    }

    private UpdateChannel channel() {
        String value = cmdParser.getChannel();
        try {
            return value == null || value.isBlank()
                ? defaultChannel : UpdateChannel.parse(value);
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return null;
        }
    }

    private PackagePlan confirmPlan(
        RepoRef ref, PackagePlan plan, Path downloaded, AppManifest previous) {
        PackageKind actual = planner.confirmAfterDownload(plan, downloaded);
        if (actual == null) {
            return null;
        }
        if (actual == plan.kind()) {
            return plan;
        }
        return planner.plan(ref, plan.asset(), actual, previous);
    }
}
