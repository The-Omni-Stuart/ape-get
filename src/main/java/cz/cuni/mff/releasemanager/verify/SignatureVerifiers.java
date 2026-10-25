package cz.cuni.mff.releasemanager.verify;

import cz.cuni.mff.releasemanager.platform.Platform;

/**
 * Selects the signature verifier for the current platform.
 */
public final class SignatureVerifiers {

    private SignatureVerifiers() {}

    public static SignatureVerifier forCurrentPlatform() {
        return switch (Platform.detectPlatform().getOS()) {
            case LINUX -> new LinuxSignatureVerifier();
            case WINDOWS -> new WindowsSignatureVerifier();
            case MAC -> new MacSignatureVerifier();
        };
    }
}
