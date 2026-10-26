package cz.cuni.mff.releasemanager.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Confirmer;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

// decides whether a downloaded release is trusted enough to install
public final class TrustGate {

    private final CmdParser cmdParser;
    private final GrmConfig toolConfig;
    private final AssetVerifier verifier;
    private final SignatureVerifier signatureVerifier;
    private final Confirmer confirm;

    public TrustGate(
        CmdParser cmdParser,
        GrmConfig toolConfig,
        AssetVerifier verifier,
        SignatureVerifier signatureVerifier,
        Confirmer confirm) {
        this.cmdParser = cmdParser;
        this.toolConfig = toolConfig;
        this.verifier = verifier;
        this.signatureVerifier = signatureVerifier;
        this.confirm = confirm;
    }

    public VerificationResult verify(
        ReleaseProvider provider, Asset asset, List<Asset> releaseAssets, Path downloaded) {
        try {
            return verifier.verify(provider, asset, releaseAssets, downloaded);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error("Checksum verification failed: " + e.getMessage());
            return null;
        }
    }

    public boolean isAllowed(VerificationResult verification, Path downloaded) {
        if (verification == null) {
            StagedAsset.discard(downloaded);
            return false;
        }
        boolean strict = cmdParser.isStrict() || toolConfig.strictVerification();
        if (!verification.allowsInstall(strict)) {
            String action = verification.status() == VerificationStatus.MISMATCH
                ? " Refusing to install."
                : " Refusing to install (strict mode).";
            Output.error(verification.message() + action);
            StagedAsset.discard(downloaded);
            return false;
        }
        if (verification.status() != VerificationStatus.VERIFIED) {
            Output.error(verification.message() + " Continuing without verification.");
        }
        return true;
    }

    public SignatureResult verifySignature(
        ReleaseProvider provider, Asset asset, List<Asset> releaseAssets, Path downloaded) {
        try {
            return signatureVerifier.verify(provider, asset, releaseAssets, downloaded);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return SignatureResult.error(asset.name(), e.getMessage());
        }
    }

    public boolean isSignatureAllowed(
        SignatureResult signature, Path downloaded, AppManifest previous) {
        if (signature == null) {
            StagedAsset.discard(downloaded);
            return false;
        }
        boolean pinned = previous != null && previous.signature() != null
            && previous.signature().isVerified();
        boolean required = cmdParser.isRequireSignature() || toolConfig.requireSignature();
        if (!signature.allowsInstall(required)) {
            Output.error(signature.message() + " Refusing to install.");
            StagedAsset.discard(downloaded);
            return false;
        }
        if (signature.status() == SignatureStatus.VERIFIED) {
            Output.info(signature.message());
        } else if (!pinned) {
            Output.error(
                signature.message() + " Continuing without a verified signature.");
        }
        return true;
    }

    public boolean isSignerAllowed(
        AppManifest previous, SignatureResult signature, Path downloaded, String operation) {
        if (previous == null || previous.signature() == null
            || !previous.signature().isVerified()) {
            return true;
        }
        AppManifest.Signature pinned = previous.signature();
        boolean unsigned = signature == null
            || signature.status() != SignatureStatus.VERIFIED
            || signature.identity() == null;
        boolean changed = !unsigned
            && !pinned.identity().equalsIgnoreCase(signature.identity());
        if (!unsigned && !changed) {
            return true;
        }

        String reason = unsigned
            ? "it is pinned to " + SignatureDescriptions.describeSignature(pinned)
                + ", but no verified signature is available"
            : "the signer changed from " + SignatureDescriptions.describeSignature(pinned)
                + " to " + describe(signature);
        if (!cmdParser.isAllowSignerChange()) {
            Output.error("Unable to " + operation + " " + previous.repo() + ".");
            Output.error("Reason: " + reason + ".");
            Output.error(
                "Be careful: this can mean the release was replaced or tampered with.");
            Output.error(unsigned
                ? "If you trust this release, rerun with --allow-signer-change "
                    + "to clear the pinned signer."
                : "If you trust the new signer, rerun with --allow-signer-change "
                    + "to accept it and update the pin.");
            StagedAsset.discard(downloaded);
            return false;
        }

        String question = unsigned
            ? "Clear the signer pin for " + previous.repo() + "?\n"
                + "  pinned: " + SignatureDescriptions.describeSignature(pinned) + "\n"
                + "  now:    no verified signature\n"
                + "Only continue if you trust this release."
            : "Accept the signer change for " + previous.repo() + "?\n"
                + "  from: " + SignatureDescriptions.describeSignature(pinned) + "\n"
                + "  to:   " + describe(signature) + "\n"
                + "Only continue if you trust the new signer.";
        if (!confirm.confirm(question)) {
            Output.error("Signer override declined.");
            StagedAsset.discard(downloaded);
            return false;
        }
        if (unsigned) {
            Output.error("Warning: signer pin cleared for " + previous.repo() + ".");
        } else {
            Output.error("Warning: signer pin updated for " + previous.repo()
                + " to " + describe(signature) + ".");
        }
        return true;
    }

    private static String describe(SignatureResult signature) {
        String signer = signature.signer() == null ? "unknown signer" : signature.signer();
        return signature.identity() == null
            ? signer
            : signer + " (" + signature.identity() + ")";
    }

    public static AppManifest.Signature signatureRecord(SignatureResult signature) {
        if (signature == null) {
            return null;
        }
        return new AppManifest.Signature(
            signature.status().name().toLowerCase(Locale.ROOT),
            signature.mechanism(),
            signature.signer(),
            signature.identity(),
            signature.notarized());
    }
}
