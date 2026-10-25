package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.nio.file.Path;

import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.SearchResult;

/**
 * A configured provider instance that can resolve releases and download assets.
 */
public interface ReleaseProvider {

    ProviderConfig config();

    /**
     * @param path repository path on this provider
     * @return latest release and its assets
     * @throws IOException if the provider request fails
     * @throws InterruptedException if the request is interrupted
     */
    ReleaseAssets latest(String path) throws IOException, InterruptedException;

    /**
     * Reads a specific release by tag.
     * @param path repository path on the provider
     * @param tag release tag
     * @return all assets and the installable subset
     * @throws IOException if the provider request fails
     * @throws InterruptedException if the request is interrupted
     */
    ReleaseAssets release(String path, String tag) throws IOException, InterruptedException;

    /**
     * @param asset asset to download
     * @return local temporary file
     * @throws IOException if the download fails
     * @throws InterruptedException if the download is interrupted
     */
    Path download(Asset asset) throws IOException, InterruptedException;

    /**
     * Searches repositories on this provider.
     * @param query free-text query
     * @return matching repositories
     * @throws IOException if the provider request fails
     * @throws InterruptedException if the request is interrupted
     */
    SearchResult search(String query) throws IOException, InterruptedException;

    /**
     * Removes a staged download.
     * @param assetPath local path
     */
    void discardAsset(Path assetPath);
}
