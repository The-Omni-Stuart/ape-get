package cz.cuni.mff.releasemanager.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import cz.cuni.mff.releasemanager.types.Asset;

/**
 * Verifies a downloaded release asset against its publisher signature.
 */
public interface SignatureVerifier {

    /**
     * Verifies a downloaded asset.
     * @param target the selected release asset
     * @param releaseAssets all assets in the release
     * @param downloaded local downloaded file
     * @return signature result
     * @throws IOException if signature material cannot be read or the verifier cannot run
     * @throws InterruptedException if verification is interrupted
     */
    SignatureResult verify(Asset target, List<Asset> releaseAssets, Path downloaded)
        throws IOException, InterruptedException;
}
