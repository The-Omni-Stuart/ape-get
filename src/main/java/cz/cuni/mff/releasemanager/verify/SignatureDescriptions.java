package cz.cuni.mff.releasemanager.verify;

import cz.cuni.mff.releasemanager.state.AppManifest;

// human-readable verification and signature summaries
public final class SignatureDescriptions {

    private SignatureDescriptions() {}

    public static String describeVerification(AppManifest.Verification verification) {
        if (verification == null) {
            return "not recorded";
        }
        StringBuilder value = new StringBuilder(verification.status());
        if (verification.method() != null) {
            value.append(" (").append(verification.method());
            if (verification.checksumAsset() != null) {
                value.append(", ").append(verification.checksumAsset());
            }
            value.append(")");
        }
        return value.toString();
    }

    public static String describePinnedSignature(AppManifest.Signature signature) {
        if (signature == null) {
            return "not recorded";
        }
        if (signature.isVerified()) {
            String value = "verified (" + signature.mechanism() + ") by "
                + describeSignature(signature);
            if (signature.notarized() != null) {
                value += signature.notarized() ? ", notarized" : ", not notarized";
            }
            return value;
        }
        return signature.status()
            + (signature.mechanism() == null ? "" : " (" + signature.mechanism() + ")");
    }

    public static String describeSignature(AppManifest.Signature signature) {
        String signer = signature.signer() == null ? "unknown signer" : signature.signer();
        return signature.identity() == null
            ? signer
            : signer + " (" + signature.identity() + ")";
    }
}
