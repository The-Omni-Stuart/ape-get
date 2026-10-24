package cz.cuni.mff.releasemanager.verify;

/**
 * Result of verifying a downloaded asset's publisher signature.
 */
public record SignatureResult(
    SignatureStatus status,
    String assetName,
    String mechanism,
    String signer,
    String identity,
    String detail
) {
    public static SignatureResult verified(
        String assetName, String mechanism, String signer, String identity) {
        return new SignatureResult(
            SignatureStatus.VERIFIED, assetName, mechanism, signer, identity, null);
    }

    public static SignatureResult missing(String assetName) {
        return new SignatureResult(
            SignatureStatus.MISSING, assetName, null, null, null, null);
    }

    public static SignatureResult unavailable(String assetName, String detail) {
        return new SignatureResult(
            SignatureStatus.UNAVAILABLE, assetName, null, null, null, detail);
    }

    public static SignatureResult invalid(
        String assetName, String mechanism, String detail) {
        return new SignatureResult(
            SignatureStatus.INVALID, assetName, mechanism, null, null, detail);
    }

    public static SignatureResult error(String assetName, String detail) {
        return new SignatureResult(
            SignatureStatus.ERROR, assetName, null, null, null, detail);
    }

    /**
     * @param required whether a missing or unavailable signature must block installation
     * @return true if installation should continue
     */
    public boolean allowsInstall(boolean required) {
        return switch (status) {
            case VERIFIED -> true;
            case INVALID, ERROR -> false;
            case MISSING, UNAVAILABLE -> !required;
        };
    }

    /**
     * @return human-readable verification message
     */
    public String message() {
        return switch (status) {
            case VERIFIED -> "Signature verified for " + assetName
                + (signer == null ? "" : " by " + signer)
                + (identity == null ? "" : " (" + identity + ")")
                + ".";
            case MISSING -> "No signature found for " + assetName + ".";
            case INVALID -> "Signature verification failed for " + assetName
                + (detail == null ? "." : ": " + detail + ".");
            case UNAVAILABLE -> "Signature verification is unavailable for " + assetName
                + (detail == null ? "." : ": " + detail + ".");
            case ERROR -> "Signature verification error for " + assetName
                + (detail == null ? "." : ": " + detail + ".");
        };
    }
}
