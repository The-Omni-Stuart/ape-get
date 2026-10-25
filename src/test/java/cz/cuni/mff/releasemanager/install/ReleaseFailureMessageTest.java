package cz.cuni.mff.releasemanager.install;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.SearchResult;

class ReleaseFailureMessageTest {

    @Test
    void readFailuresNameTheProviderAndRepository() {
        String error = captureErr(() -> new ReleaseFetcher().fetch(
            failingProvider("work", new ConnectException()), "owner/repo", null));

        assertTrue(error.contains("work:owner/repo"), error);
        assertTrue(error.contains("could not connect"), error);
    }

    @Test
    void timeoutsWithoutAMessageStillExplainThemselves() {
        String error = captureErr(() -> new ReleaseFetcher().fetch(
            failingProvider("github", new HttpTimeoutException("")),
            "owner/repo", null));

        assertTrue(error.contains("the request timed out"), error);
    }

    @Test
    void downloadFailuresNameTheProviderAndAsset() {
        String error = captureErr(() -> new ReleaseDownloader().download(
            failingProvider("node", new ConnectException()),
            new Asset("rngit://asset", "tool.AppImage")));

        assertTrue(error.contains("node:tool.AppImage"), error);
        assertTrue(error.contains("could not connect"), error);
    }

    private static ReleaseProvider failingProvider(String name, IOException error) {
        ProviderConfig config = new ProviderConfig(
            name, "gitea", "https://git.example.com", null, null);
        return new ReleaseProvider() {
            @Override
            public ProviderConfig config() {
                return config;
            }

            @Override
            public ReleaseAssets latest(String path) throws IOException {
                throw error;
            }

            @Override
            public ReleaseAssets release(String path, String tag) throws IOException {
                throw error;
            }

            @Override
            public Path download(Asset asset) throws IOException {
                throw error;
            }

            @Override
            public SearchResult search(String query) {
                return new SearchResult(List.of());
            }
        };
    }

    private static String captureErr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setErr(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
