package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.GithubClient;
import cz.cuni.mff.releasemanager.types.Asset;

/**
 * GitHub API provider implementation.
 */
public final class GithubProvider implements ReleaseProvider {
    private final GithubClient githubClient;
    private final ProviderConfig config;

    public GithubProvider(GithubClient githubClient, ProviderConfig config) {
        this.githubClient = githubClient;
        this.config = config;
    }

    @Override
    public ProviderConfig config() {
        return config;
    }

    @Override
    public ReleaseAssets latest(String path) throws IOException, InterruptedException {
        return githubClient.getLatestRelease(path);
    }

    @Override
    public Path download(Asset asset) throws IOException, InterruptedException {
        return githubClient.downloadAsset(asset);
    }

    @Override
    public void discardAsset(Path assetPath) {
        githubClient.discardAsset(assetPath);
    }
}
