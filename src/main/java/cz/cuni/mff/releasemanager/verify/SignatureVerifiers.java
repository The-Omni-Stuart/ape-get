package cz.cuni.mff.releasemanager.verify;

import java.nio.file.Path;
import java.util.List;

import cz.cuni.mff.releasemanager.GithubClient;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.types.Asset;

/**
 * Selects the signature verifier for the current platform.
 */
public final class SignatureVerifiers {

    private SignatureVerifiers() {}

    public static SignatureVerifier forCurrentPlatform(GithubClient githubClient) {
        return switch (Platform.detectPlatform().getOS()) {
            case LINUX -> new LinuxSignatureVerifier(githubClient);
            case WINDOWS, MAC -> new UnavailableSignatureVerifier();
        };
    }

    private static final class UnavailableSignatureVerifier implements SignatureVerifier {
        @Override
        public SignatureResult verify(
            Asset target, List<Asset> releaseAssets, Path downloaded) {
            return SignatureResult.unavailable(
                target.name(), "not implemented on this platform");
        }
    }
}
