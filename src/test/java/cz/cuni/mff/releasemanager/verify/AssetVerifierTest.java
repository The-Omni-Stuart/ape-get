package cz.cuni.mff.releasemanager.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.Asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssetVerifierTest {

    private static final String HELLO =
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    private static final String OTHER = "b".repeat(64);

    @TempDir
    Path tempDir;

    private final ReleaseProvider provider = mock(ReleaseProvider.class);
    private final AssetVerifier verifier = new AssetVerifier();

    private Path downloadedHello() throws Exception {
        Path downloaded = tempDir.resolve("file.txt");
        Files.writeString(downloaded, "hello", StandardCharsets.UTF_8);
        return downloaded;
    }

    private Path checksumFile(String content) throws Exception {
        Path path = tempDir.resolve("checksum.sha256");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void verifiesMatchingChecksum() throws Exception {
        Asset target = new Asset("url", "file.txt");
        Asset checksum = new Asset("url", "file.txt.sha256");
        Path checksumPath = checksumFile(HELLO + "  file.txt\n");
        when(provider.download(checksum)).thenReturn(checksumPath);

        VerificationResult result = verifier.verify(provider,
            target, List.of(target, checksum), downloadedHello());

        assertEquals(VerificationStatus.VERIFIED, result.status());
        assertTrue(result.allowsInstall(true));
        assertFalse(Files.exists(checksumPath));
    }

    @Test
    void reportsMissingChecksumAsset() throws Exception {
        Asset target = new Asset("url", "file.txt");

        VerificationResult result = verifier.verify(provider,
            target, List.of(target), downloadedHello());

        assertEquals(VerificationStatus.MISSING, result.status());
        assertTrue(result.allowsInstall(false));
        assertFalse(result.allowsInstall(true));
        verifyNoInteractions(provider);
    }

    @Test
    void reportsMismatch() throws Exception {
        Asset target = new Asset("url", "file.txt");
        Asset checksum = new Asset("url", "file.txt.sha256");
        Path checksumPath = checksumFile(OTHER + "  file.txt\n");
        when(provider.download(checksum)).thenReturn(checksumPath);

        VerificationResult result = verifier.verify(provider,
            target, List.of(target, checksum), downloadedHello());

        assertEquals(VerificationStatus.MISMATCH, result.status());
        assertFalse(result.allowsInstall(false));
        assertFalse(result.allowsInstall(true));
        assertEquals(OTHER, result.expected());
    }

    @Test
    void reportsUnknownAssetInAggregateFile() throws Exception {
        Asset target = new Asset("url", "file.txt");
        Asset checksum = new Asset("url", "SHA256SUMS");
        Path checksumPath = checksumFile(OTHER + "  other.txt\n");
        when(provider.download(checksum)).thenReturn(checksumPath);

        VerificationResult result = verifier.verify(provider,
            target, List.of(target, checksum), downloadedHello());

        assertEquals(VerificationStatus.UNKNOWN_ASSET, result.status());
        assertTrue(result.allowsInstall(false));
        assertFalse(result.allowsInstall(true));
    }

    @Test
    void reportsInvalidChecksumFile() throws Exception {
        Asset target = new Asset("url", "file.txt");
        Asset checksum = new Asset("url", "checksums.txt");
        Path checksumPath = checksumFile("garbage\n");
        when(provider.download(checksum)).thenReturn(checksumPath);

        VerificationResult result = verifier.verify(provider,
            target, List.of(target, checksum), downloadedHello());

        assertEquals(VerificationStatus.INVALID, result.status());
        assertTrue(result.allowsInstall(false));
        assertFalse(result.allowsInstall(true));
    }

    @Test
    void verifiesAgainstAggregateFile() throws Exception {
        Asset target = new Asset("url", "file.txt");
        Asset checksum = new Asset("url", "SHA256SUMS");
        Path checksumPath = checksumFile(OTHER + "  other.txt\n" + HELLO + "  file.txt\n");
        when(provider.download(checksum)).thenReturn(checksumPath);

        VerificationResult result = verifier.verify(provider,
            target, List.of(target, checksum), downloadedHello());

        assertEquals(VerificationStatus.VERIFIED, result.status());
    }
}
