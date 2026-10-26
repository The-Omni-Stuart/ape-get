package cz.cuni.mff.releasemanager.install;

import java.util.ArrayList;
import java.util.List;

import cz.cuni.mff.releasemanager.json.JsonViews.OutdatedEntry;
import cz.cuni.mff.releasemanager.platform.AssetSelector;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.platform.PlatformHandlers;
import cz.cuni.mff.releasemanager.provider.ProviderResolver;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;
import cz.cuni.mff.releasemanager.types.UpdateChannel;

// compares installed manifests with the latest release on a channel
public final class UpdateChecker {
    private final ProviderResolver resolveProvider;
    private final ReleaseFetcher fetcher;
    private final PlatformHandler platformHandler;

    public UpdateChecker(ProviderResolver resolveProvider, ReleaseFetcher fetcher) {
        this(resolveProvider, fetcher, PlatformHandlers.current());
    }

    public UpdateChecker(
        ProviderResolver resolveProvider,
        ReleaseFetcher fetcher,
        PlatformHandler platformHandler) {
        this.resolveProvider = resolveProvider;
        this.fetcher = fetcher;
        this.platformHandler = platformHandler;
    }

    public Report check(List<AppManifest> manifests, UpdateChannel channel) {
        List<OutdatedEntry> outdated = new ArrayList<>();
        boolean failed = false;
        for (AppManifest manifest : manifests) {
            RepoRef ref = new RepoRef(manifest.provider(), manifest.repo());
            ReleaseProvider provider = resolveProvider.resolve(ref.provider());
            if (provider == null) {
                failed = true;
                continue;
            }
            ReleaseAssets latest = fetcher.fetch(
                provider, ref.path(), null, channel);
            List<Asset> installable = latest == null
                ? List.of()
                : AssetSelector.installable(
                    latest.all(),
                    platformHandler.getFormats(),
                    platformHandler.getArchitecture());
            if (installable.isEmpty()) {
                failed = true;
                continue;
            }
            Asset candidate = installable.stream()
                .filter(asset -> manifest.asset() != null
                    && asset.name().equals(manifest.asset().name()))
                .findFirst()
                .orElse(installable.get(0));
            if (manifest.asset() == null
                || !candidate.url().equals(manifest.asset().url())) {
                outdated.add(new OutdatedEntry(
                    ref.provider(), ref.path(), manifest.version(),
                    latest.version(), candidate.name()));
            }
        }
        return new Report(outdated, failed);
    }

    public record Report(List<OutdatedEntry> outdated, boolean failed) {}
}
