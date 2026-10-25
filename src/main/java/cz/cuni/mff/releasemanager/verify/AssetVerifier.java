package cz.cuni.mff.releasemanager.verify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.Asset;

/**
 * Verifies a downloaded release asset against a checksum published in the same release.
 */
public final class AssetVerifier {
    private static final long MAX_CHECKSUM_BYTES = 1_048_576;

    /**
     * Verifies a downloaded asset.
     * @param provider provider that supplies the checksum asset
     * @param target the selected release asset
     * @param releaseAssets all assets in the release
     * @param downloaded local downloaded file
     * @return verification result
     * @throws IOException if a checksum asset cannot be read
     * @throws InterruptedException if a checksum download is interrupted
     */
    public VerificationResult verify(
        ReleaseProvider provider,
        Asset target,
        List<Asset> releaseAssets,
        Path downloaded
    ) throws IOException, InterruptedException {
        List<Asset> checksumAssets = ChecksumAssetFinder.find(releaseAssets, target);
        if (checksumAssets.isEmpty()) {
            return VerificationResult.missing(target.name());
        }

        boolean sawEntries = false;
        String checkedChecksum = null;

        for (Asset checksumAsset : checksumAssets) {
            Path checksumPath = provider.download(checksumAsset);
            try {
                if (Files.size(checksumPath) > MAX_CHECKSUM_BYTES) {
                    return VerificationResult.invalid(target.name(), checksumAsset.name());
                }
                String content = Files.readString(checksumPath, StandardCharsets.UTF_8);
                ChecksumFile.Lookup lookup = ChecksumFile.findSha256(content, target.name());
                if (!lookup.hasEntries()) {
                    continue;
                }
                sawEntries = true;
                checkedChecksum = checksumAsset.name();
                if (lookup.ambiguous()) {
                    return VerificationResult.invalid(target.name(), checksumAsset.name());
                }
                if (lookup.sha256().isEmpty()) {
                    continue;
                }

                String expected = lookup.sha256().get();
                String actual = sha256(downloaded);
                if (actual.equalsIgnoreCase(expected)) {
                    return VerificationResult.verified(
                        target.name(), checksumAsset.name(), expected, actual);
                }
                return VerificationResult.mismatch(
                    target.name(), expected, actual, checksumAsset.name());
            } finally {
                provider.discardAsset(checksumPath);
            }
        }

        return sawEntries
            ? VerificationResult.unknownAsset(target.name(), checkedChecksum)
            : VerificationResult.invalid(target.name(), checksumAssets.get(0).name());
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
