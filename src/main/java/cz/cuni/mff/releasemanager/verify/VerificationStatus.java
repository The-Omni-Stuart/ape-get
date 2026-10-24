package cz.cuni.mff.releasemanager.verify;

/**
 * Outcome of checking a downloaded asset against its published checksum.
 */
public enum VerificationStatus {
    VERIFIED,
    MISSING,
    UNKNOWN_ASSET,
    INVALID,
    MISMATCH
}
