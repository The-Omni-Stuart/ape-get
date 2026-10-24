package cz.cuni.mff.releasemanager.verify;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.types.Asset;

class SignatureAssetFinderTest {

    private static Asset asset(String name) {
        return new Asset("https://example.invalid/" + name, name);
    }

    @Test
    void findsSigNextToTheAsset() {
        Asset target = asset("app.AppImage");
        Asset signature = asset("app.AppImage.sig");

        Optional<Asset> found = SignatureAssetFinder.find(List.of(target, signature), target);

        assertEquals(Optional.of(signature), found);
    }

    @Test
    void findsAscNextToTheAsset() {
        Asset target = asset("app.AppImage");
        Asset signature = asset("app.AppImage.asc");

        assertTrue(SignatureAssetFinder.find(List.of(target, signature), target).isPresent());
    }

    @Test
    void ignoresSignaturesForOtherAssets() {
        Asset target = asset("app.AppImage");
        Asset other = asset("other.AppImage.sig");

        assertTrue(SignatureAssetFinder.find(List.of(target, other), target).isEmpty());
    }

    @Test
    void ignoresChecksumFiles() {
        Asset target = asset("app.AppImage");
        Asset checksum = asset("app.AppImage.sha256");

        assertTrue(SignatureAssetFinder.find(List.of(target, checksum), target).isEmpty());
    }
}
