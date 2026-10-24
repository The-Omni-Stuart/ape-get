package cz.cuni.mff.releasemanager.verify;

/**
 * Outcome of verifying a downloaded asset's publisher signature.
 */
public enum SignatureStatus {
    VERIFIED,
    MISSING,
    UNAVAILABLE,
    INVALID,
    ERROR
}
