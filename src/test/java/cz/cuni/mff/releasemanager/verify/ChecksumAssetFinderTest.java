package cz.cuni.mff.releasemanager.verify;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.types.Asset;

class ChecksumAssetFinderTest {

    private static final Asset TARGET = new Asset("url", "Example-1.0.AppImage");

    @Test
    void prefersTargetSpecificChecksumOverAggregate() {
        Asset aggregate = new Asset("url", "SHA256SUMS");
        Asset exact = new Asset("url", "Example-1.0.AppImage.sha256");

        List<Asset> found = ChecksumAssetFinder.find(List.of(aggregate, exact), TARGET);

        assertEquals(List.of(exact, aggregate), found);
    }

    @Test
    void findsAggregateFileCaseInsensitively() {
        Asset aggregate = new Asset("url", "checksums.txt");

        List<Asset> found = ChecksumAssetFinder.find(List.of(aggregate, TARGET), TARGET);

        assertEquals(List.of(aggregate), found);
    }

    @Test
    void findsSha256SumSuffix() {
        Asset checksum = new Asset("url", "Example-1.0.AppImage.sha256sum");

        List<Asset> found = ChecksumAssetFinder.find(List.of(checksum), TARGET);

        assertEquals(List.of(checksum), found);
    }

    @Test
    void findsDigestSuffix() {
        Asset checksum = new Asset("url", "Example-1.0.AppImage.DIGEST");

        List<Asset> found = ChecksumAssetFinder.find(List.of(checksum), TARGET);

        assertEquals(List.of(checksum), found);
    }

    @Test
    void returnsEmptyWhenNoChecksumIsPublished() {
        List<Asset> found = ChecksumAssetFinder.find(
            List.of(TARGET, new Asset("url", "notes.txt")), TARGET);

        assertTrue(found.isEmpty());
    }
}
