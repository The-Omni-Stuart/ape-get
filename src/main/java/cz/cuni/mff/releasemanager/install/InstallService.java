package cz.cuni.mff.releasemanager.install;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.provider.ProviderResolver;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
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

    public InstallService(
        CmdParser cmdParser,
        ManifestStore manifests,
        ProviderResolver resolveProvider,
        InstallComponents components) {
        this.cmdParser = cmdParser;
        this.manifests = manifests;
        this.resolveProvider = resolveProvider;
        this.components = components;
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
        ReleaseAssets release = components.fetcher().fetch(
            provider, ref.path(), cmdParser.getVersion());
        if (release == null || release.installable().isEmpty()) {
            Output.error("Failed to retrieve the release.");
            return false;
        }
        Asset asset = components.chooser().choose(release.installable());
        if (asset == null) {
            return false;
        }
        if (cmdParser.isDryRun()) {
            components.reporter().printPlan("install", ref, release, asset);
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
        Path installed = components.platform().install(
            downloaded, ref, release.version());
        if (installed == null) {
            Output.error("Installation failed.");
            return false;
        }
        AppManifest manifest = components.manifestFactory().create(
            ref, provider.config(), release, asset, installed, verification,
            signature, Instant.now(), List.of());
        if (!manifests.save(manifest)) {
            return false;
        }
        components.reporter().installed(ref, release, asset, installed);
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
        ReleaseProvider provider = resolveProvider.resolve(previous.provider());
        if (provider == null) {
            return false;
        }
        ReleaseAssets latest = components.fetcher().fetch(
            provider, previous.repo(), cmdParser.getVersion());
        if (latest == null) {
            return false;
        }
        List<Asset> assets = latest.installable();
        if (assets.isEmpty()) {
            Output.error("No asset found.");
            return false;
        }
        Asset newAsset = components.chooser().chooseForUpdate(assets, previous);
        if (newAsset == null) {
            return false;
        }
        if (previous.asset().url().equals(newAsset.url())) {
            components.reporter().upToDate(ref, previous.version());
            return true;
        }
        if (cmdParser.isDryRun()) {
            components.reporter().printPlan("update", ref, latest, newAsset);
            return true;
        }
        Path downloaded = components.downloader().download(provider, newAsset);
        if (downloaded == null) {
            return false;
        }
        TrustGate gate = components.trust().create();
        VerificationResult verification = gate.verify(
            provider, newAsset, latest.all(), downloaded);
        if (!gate.isAllowed(verification, downloaded)) {
            return false;
        }
        SignatureResult signature = gate.verifySignature(
            provider, newAsset, latest.all(), downloaded);
        if (!gate.isSignatureAllowed(signature, downloaded, previous)) {
            return false;
        }
        if (!gate.isSignerAllowed(previous, signature, downloaded, "update")) {
            return false;
        }
        List<AppManifest.Backup> backupList;
        try {
            backupList = components.backups().forUpdate(previous);
        } catch (IOException e) {
            Output.error("Failed to create backup; update aborted: " + e.getMessage());
            StagedAsset.discard(downloaded);
            return false;
        }
        Path installed = components.platform().install(downloaded, ref, latest.version());
        if (installed == null) {
            Output.error("Installation failed.");
            return false;
        }
        AppManifest updated = components.manifestFactory().create(
            ref, provider.config(), latest, newAsset, installed, verification,
            signature, previous.installedAt(), backupList);
        if (!manifests.save(updated)) {
            return false;
        }
        components.reporter().updated(ref, latest, newAsset, installed);
        return true;
    }
}
