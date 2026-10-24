package cz.cuni.mff.releasemanager.provider;

import java.util.List;

import cz.cuni.mff.releasemanager.types.Asset;

/**
 * Release identity and assets returned by a provider.
 */
public record ReleaseAssets(
    String version,
    Long releaseId,
    List<Asset> all,
    List<Asset> installable
) {
    public ReleaseAssets(List<Asset> all, List<Asset> installable) {
        this(null, null, all, installable);
    }
}
