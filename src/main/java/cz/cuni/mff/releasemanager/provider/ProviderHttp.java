package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

/**
 * Shared HTTP plumbing for every HTTP provider.
 */
final class ProviderHttp {
    record Response(int status, String body, HttpHeaders headers) {}

    private final ProviderConfig config;
    private final String label;
    private final String authHeader;
    private final String authPrefix;

    private volatile HttpClient client;

    ProviderHttp(
        ProviderConfig config,
        String label,
        String authHeader,
        String authPrefix) {
        this(config, null, label, authHeader, authPrefix);
    }

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

    String apiBase() {
        return config.resolvedApi();
    }

    String get(String url, String context) throws IOException, InterruptedException {
        Response response = send(url, "application/json", context);
        requireSuccess(response, context);
        return response.body();
    }

    Response send(String url, String accept, String context)
        throws IOException, InterruptedException {
        HttpRequest request = request(url, NetworkSettings.apiTimeout())
            .header("Accept", accept)
            .GET()
            .build();
        try {
            HttpResponse<String> response =
                client().send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(
                response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw networkError(e, request.uri(), "request");
        }
    }

    void requireSuccess(Response response, String context) throws IOException {
        requireStatus(response.status(), null, context, "API", "resource not found");
    }

    Path download(Asset asset) throws IOException, InterruptedException {
        return download(asset, NetworkSettings.downloadTimeout());
    }

    Path download(Asset asset, Duration timeout)
        throws IOException, InterruptedException {
        Path destination = StagedAsset.create(asset.name());
        try {
            HttpRequest request = request(asset.url(), timeout)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
            HttpResponse<Path> response;
            try {
                response = client().send(
                    request, HttpResponse.BodyHandlers.ofFile(destination));
            } catch (IOException e) {
                throw networkError(e, request.uri(), "download");
            }
            requireDownloadSuccess(
                response.statusCode(), response.headers(), asset.name());
            StagedAsset.requireContent(destination, asset.name());
            return destination.toAbsolutePath();
        } catch (IOException | InterruptedException e) {
            StagedAsset.discard(destination);
            throw e;
        }
    }

    HttpClient client() {
        HttpClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    HttpClient.Builder builder = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .version(HttpClient.Version.HTTP_2);
                    NetworkSettings.apply(builder);
                    current = builder.build();
                    client = current;
                }
            }
        }
        return current;
    }

    private void requireDownloadSuccess(
        int status, HttpHeaders headers, String name) throws IOException {
        requireStatus(status, headers, name, "download", "asset not found");
    }

    private void requireStatus(
        int status,
        HttpHeaders headers,
        String context,
        String action,
        String notFoundMessage) throws IOException {
        if (status == 200) {
            return;
        }
        if (status == 401) {
            throw new ProviderException(
                ProviderException.Reason.UNAUTHORIZED, "authentication failed");
        }
        if (status == 403 && headers != null
            && headers.firstValueAsLong("X-RateLimit-Remaining").orElse(-1) == 0) {
            throw new ProviderException(
                ProviderException.Reason.RATE_LIMITED,
                "Rate limit exceeded; try again later.");
        }
        if (status == 403) {
            throw new ProviderException(
                ProviderException.Reason.UNAUTHORIZED, "access denied");
        }
        if (status == 404) {
            throw new ProviderException(
                ProviderException.Reason.NOT_FOUND, notFoundMessage);
        }
        if (status == 429) {
            throw new ProviderException(
                ProviderException.Reason.RATE_LIMITED, "rate limit exceeded");
        }
        throw new IOException(label + " " + action + " returned " + status
            + " for " + context);
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

    private Optional<String> token() {
        String env = config.tokenEnv();
        if (env == null || env.isBlank()) {
            return Optional.empty();
        }
        String value = System.getenv(env);
        return value == null || value.isBlank()
            ? Optional.empty() : Optional.of(value);
    }
}
