package cz.cuni.mff.releasemanager.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.ManifestViews;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.Output;

// decides whether a downloaded release is trusted enough to install
public final class TrustGate {

    private final CmdParser cmdParser;
    private final GrmConfig toolConfig;
    private final AssetVerifier verifier;
    private final SignatureVerifier signatureVerifier;
    private final Consumer<Path> discardAsset;
    private final Predicate<String> confirm;

    public TrustGate(
        CmdParser cmdParser,
        GrmConfig toolConfig,
        AssetVerifier verifier,
        SignatureVerifier signatureVerifier,
        Consumer<Path> discardAsset,
        Predicate<String> confirm) {
        this.cmdParser = cmdParser;
        this.toolConfig = toolConfig;
        this.verifier = verifier;
        this.signatureVerifier = signatureVerifier;
        this.discardAsset = discardAsset;
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
            System.err.println("Checksum verification failed: " + e.getMessage());
            return null;
        }
    }

    public boolean isAllowed(VerificationResult verification, Path downloaded) {
        if (verification == null) {
            discardAsset.accept(downloaded);
            return false;
        }
        boolean strict = cmdParser.isStrict() || toolConfig.strictVerification();
        if (!verification.allowsInstall(strict)) {
            String action = verification.status() == VerificationStatus.MISMATCH
                ? " Refusing to install."
                : " Refusing to install (strict mode).";
            System.err.println(verification.message() + action);
            discardAsset.accept(downloaded);
            return false;
        }
        if (verification.status() != VerificationStatus.VERIFIED) {
            System.err.println(verification.message() + " Continuing without verification.");
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
            discardAsset.accept(downloaded);
            return false;
        }
        boolean pinned = previous != null && previous.signature() != null
            && previous.signature().isVerified();
        boolean required = cmdParser.isRequireSignature() || toolConfig.requireSignature();
        if (!signature.allowsInstall(required)) {
            System.err.println(signature.message() + " Refusing to install.");
            discardAsset.accept(downloaded);
            return false;
        }
        if (signature.status() == SignatureStatus.VERIFIED) {
            Output.info(signature.message());
        } else if (!pinned) {
            System.err.println(
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
            ? "it is pinned to " + ManifestViews.describeSignature(pinned)
                + ", but no verified signature is available"
            : "the signer changed from " + ManifestViews.describeSignature(pinned)
                + " to " + describe(signature);
        if (!cmdParser.isAllowSignerChange()) {
            System.err.println("Unable to " + operation + " " + previous.repo() + ".");
            System.err.println("Reason: " + reason + ".");
            System.err.println(
                "Be careful: this can mean the release was replaced or tampered with.");
            System.err.println(unsigned
                ? "If you trust this release, rerun with --allow-signer-change "
                    + "to clear the pinned signer."
                : "If you trust the new signer, rerun with --allow-signer-change "
                    + "to accept it and update the pin.");
            discardAsset.accept(downloaded);
            return false;
        }

        String question = unsigned
            ? "Clear the signer pin for " + previous.repo() + "?\n"
                + "  pinned: " + ManifestViews.describeSignature(pinned) + "\n"
                + "  now:    no verified signature\n"
                + "Only continue if you trust this release."
            : "Accept the signer change for " + previous.repo() + "?\n"
                + "  from: " + ManifestViews.describeSignature(pinned) + "\n"
                + "  to:   " + describe(signature) + "\n"
                + "Only continue if you trust the new signer.";
        if (!confirm.test(question)) {
            System.err.println("Signer override declined.");
            discardAsset.accept(downloaded);
            return false;
        }
        if (unsigned) {
            System.err.println("Warning: signer pin cleared for " + previous.repo() + ".");
        } else {
            System.err.println("Warning: signer pin updated for " + previous.repo()
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
