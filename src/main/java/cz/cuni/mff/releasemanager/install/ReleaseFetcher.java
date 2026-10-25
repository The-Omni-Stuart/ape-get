package cz.cuni.mff.releasemanager.install;

import java.io.IOException;

import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.utils.Output;

// reads the latest release, or the tag the user asked for
public final class ReleaseFetcher {

    public ReleaseAssets fetch(ReleaseProvider provider, String path, String version) {
        try {
            return version == null || version.isBlank()
                ? provider.latest(path)
                : provider.release(path, version);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Output.error("Failed to read release: " + e.getMessage());
            return null;
        }
    }
}
