package cz.cuni.mff.releasemanager.verify;

/**
 * Result of verifying a downloaded asset against a published checksum.
 */
public record VerificationResult(
    VerificationStatus status,
    String assetName,
    String expected,
    String actual,
    String checksumAssetName
) {
    public static VerificationResult verified(String assetName, String checksumAssetName) {
        return new VerificationResult(VerificationStatus.VERIFIED, assetName, null, null, checksumAssetName);
    }

    public static VerificationResult missing(String assetName) {
        return new VerificationResult(VerificationStatus.MISSING, assetName, null, null, null);
    }

    public static VerificationResult unknownAsset(String assetName, String checksumAssetName) {
        return new VerificationResult(
            VerificationStatus.UNKNOWN_ASSET, assetName, null, null, checksumAssetName);
    }

    public static VerificationResult invalid(String assetName, String checksumAssetName) {
        return new VerificationResult(
            VerificationStatus.INVALID, assetName, null, null, checksumAssetName);
    }

    public static VerificationResult mismatch(
        String assetName, String expected, String actual, String checksumAssetName) {
        return new VerificationResult(
            VerificationStatus.MISMATCH, assetName, expected, actual, checksumAssetName);
    }

    /**
     * @param strict whether a missing or unusable checksum must block installation
     * @return true if installation should continue
     */
    public boolean allowsInstall(boolean strict) {
        return switch (status) {
            case VERIFIED -> true;
            case MISMATCH -> false;
            case MISSING, UNKNOWN_ASSET, INVALID -> !strict;
        };
    }

    /**
     * @return human-readable verification message
     */
    public String message() {
        return switch (status) {
            case VERIFIED -> "Checksum verified for " + assetName + ".";
            case MISSING -> "No checksum found for " + assetName + ".";
            case UNKNOWN_ASSET -> "Checksum file does not contain " + assetName + ".";
            case INVALID -> "Checksum file is invalid for " + assetName + ".";
            case MISMATCH -> "Checksum mismatch for " + assetName
                + ": expected " + expected + ", got " + actual + ".";
        };
    }
}
