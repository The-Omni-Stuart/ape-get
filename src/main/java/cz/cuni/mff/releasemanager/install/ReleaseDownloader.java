package cz.cuni.mff.releasemanager.install;

import java.io.IOException;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.NetworkErrors;
import cz.cuni.mff.releasemanager.utils.Output;

// downloads one asset to a temp path
public final class ReleaseDownloader {

    public Path download(ReleaseProvider provider, Asset asset) {
        try {
            return provider.download(asset);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String name = provider.config() == null
                ? null : provider.config().name();
            String source = name == null || name.isBlank()
                ? asset.name() : name + ":" + asset.name();
            Output.error("Failed to download " + source + ": "
                + NetworkErrors.describe(e));
            return null;
        }
    }
}
