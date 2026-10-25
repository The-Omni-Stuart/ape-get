package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

// shared HTTP plumbing for the Gitea and GitLab adapters
final class ProviderHttp {
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
        HttpRequest request = request(url, NetworkSettings.apiTimeout())
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw networkError(e, request.uri(), "request");
        }
        authenticate(response.statusCode());
        if (response.statusCode() == 404) {
            throw new ProviderException(
                ProviderException.Reason.NOT_FOUND, "repository or release not found");
        }
        if (response.statusCode() == 429) {
            throw new ProviderException(
                ProviderException.Reason.RATE_LIMITED, "rate limit exceeded");
        }
        if (response.statusCode() != 200) {
            throw new IOException(label + " API returned " + response.statusCode()
                + " for " + context);
        }
        return response.body();
    }

    Path download(Asset asset) throws IOException, InterruptedException {
        Path destination = StagedAsset.create(asset.name());
        try {
            HttpRequest request = request(
                asset.url(), NetworkSettings.downloadTimeout()).GET().build();
            HttpResponse<Path> response;
            try {
                response = client.send(
                    request, HttpResponse.BodyHandlers.ofFile(destination));
            } catch (IOException e) {
                throw networkError(e, request.uri(), "download");
            }
            if (response.statusCode() == 404) {
                throw new ProviderException(
                    ProviderException.Reason.NOT_FOUND, "asset not found");
            }
            if (response.statusCode() == 429) {
                throw new ProviderException(
                    ProviderException.Reason.RATE_LIMITED, "rate limit exceeded");
            }
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

    private IOException networkError(
        IOException error, URI uri, String action) {
        if (error instanceof HttpTimeoutException) {
            Duration timeout = "download".equals(action)
                ? NetworkSettings.downloadTimeout() : NetworkSettings.apiTimeout();
            return new ProviderException(
                ProviderException.Reason.TIMEOUT,
                label + " " + action + " timed out after "
                    + timeout.toSeconds() + "s", error);
        }
        if (error instanceof UnknownHostException) {
            return new ProviderException(
                ProviderException.Reason.UNREACHABLE,
                label + " could not resolve " + uri.getHost(), error);
        }
        if (error instanceof ConnectException) {
            return new ProviderException(
                ProviderException.Reason.UNREACHABLE,
                label + " could not connect to " + uri.getHost(), error);
        }
        return error;
    }

    private void authenticate(int status) throws IOException {
        if (status == 401 || status == 403) {
            throw new ProviderException(
                ProviderException.Reason.UNAUTHORIZED, "authentication failed");
        }
    }

    private Optional<String> token() {
        String env = config.tokenEnv();
        if (env == null || env.isBlank()) {
            return Optional.empty();
        }
        String value = System.getenv(env);
        if (value == null || value.isBlank()) {
            Output.error("Warning: provider " + config.name()
                + " expects a token in " + env + ".");
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
