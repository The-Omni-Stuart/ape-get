package cz.cuni.mff.releasemanager.install;

import java.io.IOException;

import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.UpdateChannel;
import cz.cuni.mff.releasemanager.utils.NetworkErrors;
import cz.cuni.mff.releasemanager.utils.Output;

// reads the latest release, or the tag the user asked for
public final class ReleaseFetcher {

    public ReleaseAssets fetch(ReleaseProvider provider, String path, String version) {
        return fetch(provider, path, version, UpdateChannel.STABLE);
    }

    public ReleaseAssets fetch(
        ReleaseProvider provider, String path, String version, UpdateChannel channel) {
        try {
            return version == null || version.isBlank()
                ? provider.latest(path, channel)
                : provider.release(path, version);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String name = provider.config() == null
                ? null : provider.config().name();
            String target = name == null || name.isBlank()
                ? path : name + ":" + path;
            Output.error("Failed to read releases from " + target + ": "
                + NetworkErrors.describe(e));
            return null;
        }
    }
}
