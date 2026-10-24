package cz.cuni.mff.releasemanager.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import cz.cuni.mff.releasemanager.types.Asset;

/**
 * Finds checksum assets that may describe a selected release asset.
 */
public final class ChecksumAssetFinder {
    private static final Set<String> AGGREGATE_NAMES = Set.of(
        "sha256sums",
        "sha256sums.txt",
        "sha256sums.sha256",
        "sha256sum.txt",
        "checksums.txt",
        "checksums.sha256",
        "checksum.txt",
        "sha256.txt"
    );

    private ChecksumAssetFinder() {}

    /**
     * Finds candidate checksum assets, preferring target-specific files over aggregate files.
     * @param assets all assets in the release
     * @param target selected asset
     * @return ordered checksum candidates
     */
    public static List<Asset> find(List<Asset> assets, Asset target) {
        if (assets == null || target == null || target.name() == null) {
            return List.of();
        }
        String targetName = target.name().toLowerCase(Locale.ROOT);
        List<Asset> exact = new ArrayList<>();
        List<Asset> aggregate = new ArrayList<>();

        for (Asset asset : assets) {
            if (asset == null || asset.name() == null) {
                continue;
            }
            String name = asset.name().toLowerCase(Locale.ROOT);
            if (name.equals(targetName + ".sha256")
                || name.equals(targetName + ".sha256sum")
                || name.equals(targetName + ".sha256.txt")
                || name.equals(targetName + ".digest")) {
                exact.add(asset);
            } else if (AGGREGATE_NAMES.contains(name)) {
                aggregate.add(asset);
            }
        }

        List<Asset> result = new ArrayList<>(exact.size() + aggregate.size());
        result.addAll(exact);
        result.addAll(aggregate);
        return result;
    }
}
