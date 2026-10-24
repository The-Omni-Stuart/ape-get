package cz.cuni.mff.releasemanager.verify;

import java.util.List;
import java.util.Optional;

import cz.cuni.mff.releasemanager.types.Asset;

/**
 * Finds a detached signature published next to a release asset.
 */
final class SignatureAssetFinder {

    private SignatureAssetFinder() {}

    static Optional<Asset> find(List<Asset> assets, Asset target) {
        String signature = target.name() + ".sig";
        String armored = target.name() + ".asc";
        for (Asset asset : assets) {
            String name = asset.name();
            if (name.equalsIgnoreCase(signature) || name.equalsIgnoreCase(armored)) {
                return Optional.of(asset);
            }
        }
        return Optional.empty();
    }
}
