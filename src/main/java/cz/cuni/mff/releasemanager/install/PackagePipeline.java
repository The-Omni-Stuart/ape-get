package cz.cuni.mff.releasemanager.install;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.platform.PackageKind;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.ManifestStore;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.StagedAsset;
import cz.cuni.mff.releasemanager.verify.SignatureResult;
import cz.cuni.mff.releasemanager.verify.TrustGate;
import cz.cuni.mff.releasemanager.verify.VerificationResult;

/**
 * The common install/update pipeline: choose, confirm, download, verify, install, record.
 */
final class PackagePipeline {
    enum Action { INSTALL, UPDATE }

    enum Status { INSTALLED, UPDATED, FAILED }

    record Selection(ReleaseAssets release, Asset asset, PackagePlan plan) {}

    record Result(Status status, String message) {}

    private final CmdParser cmdParser;
    private final ManifestStore manifests;
    private final InstallComponents components;
    private final PackagePlanner planner;

    PackagePipeline(
        CmdParser cmdParser,
        ManifestStore manifests,
        InstallComponents components,
        PackagePlanner planner) {
        this.cmdParser = cmdParser;
        this.manifests = manifests;
        this.components = components;
        this.planner = planner;
    }

    // choose the asset and plan without touching the network or disk
    Selection select(RepoRef ref, ReleaseAssets release, AppManifest previous) {
        PackageKind requested;
        try {
            requested = planner.requestedKind();
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return null;
        }
        List<Asset> candidates = planner.candidates(
            assetPool(release), requested, cmdParser.getAsset() != null);
        if (candidates.isEmpty()) {
            Output.error("No compatible package found for this platform.");
            return null;
        }
        Asset asset = previous == null
            ? components.chooser().choose(candidates)
            : components.chooser().chooseForUpdate(candidates, previous);
        if (asset == null) {
            return null;
        }
        PackagePlan plan = planner.plan(ref, asset, requested, previous);
        if (plan == null) {
            return null;
        }
        if (!commandIsFree(ref, plan.command())) {
            return null;
        }
        return new Selection(release, asset, plan);
    }

    Result run(
        Action action,
        RepoRef ref,
        ReleaseProvider provider,
        ReleaseAssets release,
        AppManifest previous,
        Selection selection,
        boolean report,
        boolean ask) {
        Asset asset = selection.asset();
        PackagePlan plan = selection.plan();
        boolean installing = action == Action.INSTALL;
        String from = previous == null || previous.version() == null
            ? "unknown" : previous.version();
        String detail = installing
            ? ref.display() + " " + release.version() + " (" + asset.name() + ")"
            : ref.display() + " " + from + " -> " + release.version()
                + " (" + asset.name() + ")";
        if (ask && !components.confirmation().allowed(
            installing ? "Install" : "Update", detail)) {
            return new Result(Status.FAILED, null);
        }

        Path downloaded = components.downloader().download(provider, asset);
        if (downloaded == null) {
            return new Result(Status.FAILED, null);
        }

        TrustGate gate = components.trust().create();
        VerificationResult verification = gate.verify(
            provider, asset, release.all(), downloaded);
        if (!gate.isAllowed(verification, downloaded)) {
            return new Result(Status.FAILED, null);
        }
        SignatureResult signature = gate.verifySignature(
            provider, asset, release.all(), downloaded);
        if (!gate.isSignatureAllowed(signature, downloaded, previous)) {
            return new Result(Status.FAILED, null);
        }
        if (!gate.isSignerAllowed(previous, signature, downloaded,
            installing ? "install" : "update")) {
            return new Result(Status.FAILED, null);
        }

        PackagePlan confirmed = confirmPlan(ref, plan, downloaded, previous);
        if (confirmed == null) {
            StagedAsset.discard(downloaded);
            return new Result(Status.FAILED, null);
        }

        List<AppManifest.Backup> backups = List.of();
        if (!installing) {
            try {
                backups = components.backups().forUpdate(previous);
            } catch (IOException e) {
                StagedAsset.discard(downloaded);
                return new Result(Status.FAILED,
                    "Failed to create backup; update aborted: " + e.getMessage());
            }
        }

        Path installed = components.platform().install(
            downloaded, ref, release.version(),
            confirmed.kind(), confirmed.command(), cmdParser.isAssimilate(),
            cmdParser.isDesktop(), cmdParser.getIcon(), cmdParser.isTerminal(),
            cmdParser.isNoDesktop());
        if (installed == null) {
            return new Result(Status.FAILED, "Installation failed.");
        }

        AppManifest manifest = components.manifestFactory().create(
            ref, provider.config(), release, asset,
            confirmed.kind(), confirmed.command(), installed,
            verification, signature,
            installing ? Instant.now() : previous.installedAt(), backups);
        if (!manifests.save(manifest)) {
            return new Result(Status.FAILED, null);
        }

        if (report) {
            if (installing) {
                components.reporter().installed(ref, release, confirmed, installed);
            } else {
                components.reporter().updated(ref, release, confirmed, installed);
            }
        }
        return new Result(installing ? Status.INSTALLED : Status.UPDATED, null);
    }

    private List<Asset> assetPool(ReleaseAssets release) {
        String requested = cmdParser.getAsset();
        if (requested == null || requested.isBlank()
            || requested.trim().matches("\\d+")) {
            return components.platform().installableAssets(release.all());
        }
        return release.all();
    }

    private boolean commandIsFree(RepoRef ref, String command) {
        if (command == null || command.isBlank()) {
            return true;
        }
        try {
            for (AppManifest manifest : manifests.scan()) {
                if (manifest == null || manifest.install() == null
                    || !command.equals(manifest.install().command())) {
                    continue;
                }
                if (ref.provider().equals(manifest.provider())
                    && ref.path().equals(manifest.repo())) {
                    continue;
                }
                Output.error("Command '" + command + "' is already used by "
                    + manifest.provider() + ":" + manifest.repo()
                    + "; choose another with --name.");
                return false;
            }
            return true;
        } catch (IOException e) {
            Output.error("Failed to check installed commands: " + e.getMessage());
            return false;
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
