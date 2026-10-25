package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

// shared HTTP plumbing for the Gitea and GitLab adapters
final class ProviderHttp {
    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    private final ProviderConfig config;
    private final HttpClient client;
    private final String label;
    private final String authHeader;
    private final String authPrefix;

    ProviderHttp(
        ProviderConfig config,
        HttpClient client,
        String label,
        String authHeader,
        String authPrefix) {
        this.config = config;
        this.client = client;
        this.label = label;
        this.authHeader = authHeader;
        this.authPrefix = authPrefix;
    }

    String get(String url, String context) throws IOException, InterruptedException {
        HttpRequest request = request(url, API_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());
        authenticate(response.statusCode());
        if (response.statusCode() != 200) {
            throw new IOException(label + " API returned " + response.statusCode()
                + " for " + context);
        }
        return response.body();
    }

    Path download(Asset asset) throws IOException, InterruptedException {
        Path destination = StagedAsset.create(asset.name());
        try {
            HttpRequest request = request(asset.url(), DOWNLOAD_TIMEOUT).GET().build();
            HttpResponse<Path> response =
                client.send(request, HttpResponse.BodyHandlers.ofFile(destination));
            if (response.statusCode() != 200) {
                throw new IOException(label + " download returned "
                    + response.statusCode() + " for " + asset.name());
            }
            StagedAsset.requireContent(destination, asset.name());
            return destination.toAbsolutePath();
        } catch (IOException | InterruptedException e) {
            StagedAsset.discard(destination);
            throw e;
        }
    }

    void discard(Path assetPath) {
        StagedAsset.discard(assetPath);
    }

    private HttpRequest.Builder request(String url, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "ape-get")
            .timeout(timeout);
        if (config.allowsTokenFor(url)) {
            token().ifPresent(
                value -> builder.header(authHeader, authPrefix + value));
        }
        return builder;
    }

    private void authenticate(int status) throws IOException {
        if (status == 401 || status == 403) {
            throw new IOException(
                label + " authentication failed for " + config.name());
        }
    }

    private Optional<String> token() {
        String env = config.tokenEnv();
        if (env == null || env.isBlank()) {
            return Optional.empty();
        }
        String value = System.getenv(env);
        if (value == null || value.isBlank()) {
            System.err.println("Warning: provider " + config.name()
                + " expects a token in " + env + ".");
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
