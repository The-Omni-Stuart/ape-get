package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.SearchResult;

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
    public ReleaseAssets latest(String path, UpdateChannel channel)
        throws IOException, InterruptedException {
        return channel == UpdateChannel.BETA
            ? githubClient.getLatestRelease(path, channel)
            : githubClient.getLatestRelease(path);
    }

    @Override
    public ReleaseAssets release(String path, String tag) throws IOException, InterruptedException {
        return githubClient.getRelease(path, tag);
    }

    @Override
    public Path download(Asset asset) throws IOException, InterruptedException {
        return githubClient.downloadAsset(asset);
    }

    @Override
    public SearchResult search(String query) throws IOException, InterruptedException {
        return githubClient.searchRepoByName(query)
            .orElse(new SearchResult(java.util.List.of()));
    }
}
