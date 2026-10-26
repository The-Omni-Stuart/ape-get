package cz.cuni.mff.releasemanager.install;

import java.io.IOException;
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
import cz.cuni.mff.releasemanager.types.UpdateChannel;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.RepoParser;

// install/update: fetch and select here, then hand the shared work to the pipeline
public final class InstallService {
    private final CmdParser cmdParser;
    private final ManifestStore manifests;
    private final ProviderResolver resolveProvider;
    private final InstallComponents components;
    private final PackagePipeline pipeline;
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
        this.pipeline = new PackagePipeline(cmdParser, manifests, components, planner);
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
        if (release == null) {
            Output.error("Failed to retrieve the release.");
            return false;
        }
        PackagePipeline.Selection selection = pipeline.select(ref, release, null);
        if (selection == null) {
            return false;
        }
        if (cmdParser.isDryRun()) {
            components.reporter().printPlan("install", ref, release, selection.plan());
            return true;
        }
        PackagePipeline.Result result = pipeline.run(
            PackagePipeline.Action.INSTALL, ref, provider, release, null,
            selection, true, true);
        if (result.status() == PackagePipeline.Status.FAILED) {
            if (result.message() != null) {
                Output.error(result.message());
            }
            return false;
        }
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
        return updateOne(previous, cmdParser.getVersion(), true, true).status()
            != UpdateResult.Status.FAILED;
    }

    public record Batch(boolean ok, List<UpdateResult> results) {}

    public boolean updateAll(boolean report) {
        return updateAll(report, true);
    }

    public boolean updateAll(boolean report, boolean ask) {
        return updateAllBatch(report, ask).ok();
    }

    public Batch updateAllBatch(boolean report, boolean ask) {
        List<AppManifest> installed;
        try {
            installed = manifests.scan();
        } catch (IOException e) {
            Output.error("Failed to load applications: " + e.getMessage());
            return new Batch(false, List.of());
        }
        if (ask && !installed.isEmpty()
            && !components.confirmation().allowed("Update",
                installed.size() + " installed package(s)")) {
            return new Batch(false, List.of());
        }
        List<UpdateResult> results = new java.util.ArrayList<>();
        for (AppManifest previous : installed) {
            results.add(updateOne(previous, null, false, false));
        }
        if (report) {
            components.reporter().batch(results);
        }
        boolean ok = results.stream()
            .noneMatch(result -> result.status() == UpdateResult.Status.FAILED);
        return new Batch(ok, results);
    }

    private UpdateResult updateOne(
        AppManifest previous, String version, boolean report, boolean ask) {
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
        PackagePipeline.Selection selection = pipeline.select(ref, latest, previous);
        if (selection == null) {
            return failed(ref, fromVersion, null);
        }
        Asset newAsset = selection.asset();
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
                components.reporter().printPlan("update", ref, latest, selection.plan());
            }
            return new UpdateResult(
                ref.provider(), ref.path(), fromVersion, latest.version(),
                UpdateResult.Status.PLANNED, null);
        }
        PackagePipeline.Result result = pipeline.run(
            PackagePipeline.Action.UPDATE, ref, provider, latest, previous,
            selection, report, ask);
        if (result.status() == PackagePipeline.Status.FAILED) {
            return failed(ref, fromVersion, result.message());
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
}
