package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import cz.cuni.mff.releasemanager.platform.AssetSelector;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.Release;
import cz.cuni.mff.releasemanager.types.SearchResult;
import cz.cuni.mff.releasemanager.utils.Output;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

// GitHub API calls and asset downloads
public class GithubClient {

    private static final String DEFAULT_API_URL = "https://api.github.com";
    private static final String DEFAULT_TOKEN_ENV = "GRM_GITHUB_TOKEN";
    private static final String ACCEPT_JSON_HEADER = "application/vnd.github.v3+json";
    private static final String ACCEPT_STREAM_HEADER = "application/octet-stream";
    private static final String RESULT_COUNT = "5";
    private volatile HttpClient client;
    private final PlatformHandler platformHandler;
    private final Duration downloadTimeout;
    private final String apiBase;
    private final String token;

    // default github.com endpoint with the standard token variable
    public GithubClient() {
        this(DEFAULT_API_URL, DEFAULT_TOKEN_ENV, null);
    }

    public GithubClient(String apiBase, String tokenEnv) {
        this(apiBase, tokenEnv, null);
    }

    GithubClient(Duration downloadTimeout) {
        this(DEFAULT_API_URL, DEFAULT_TOKEN_ENV, downloadTimeout);
    }

    GithubClient(String apiBase, String tokenEnv, Duration downloadTimeout) {
        this.apiBase = apiBase.endsWith("/")
            ? apiBase.substring(0, apiBase.length() - 1)
            : apiBase;
        this.downloadTimeout = downloadTimeout;
        String value = tokenEnv == null || tokenEnv.isBlank()
            ? null : System.getenv(tokenEnv);
        this.token = value == null || value.isBlank() ? null : value;
        platformHandler = Platform.getPlatformHandler();
    }

    // built on first use so config-loaded proxy and timeout settings apply
    private HttpClient client() {
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

    private Duration downloadTimeout() {
        return downloadTimeout == null
            ? NetworkSettings.downloadTimeout() : downloadTimeout;
    }

    // search repositories by name
    public Optional<SearchResult> searchRepoByName(String name)
        throws IOException, InterruptedException {
        String query = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String url = apiBase + "/search/repositories?q=" + query + "&per_page=" + RESULT_COUNT;
        var jsonResponse = request(URI.create(url));
        if (jsonResponse.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(getSearchResult(jsonResponse.get()));
    }

    // latest release with all assets and the installable subset
    public ReleaseAssets getLatestRelease(String repoFullName)
        throws IOException, InterruptedException {
        return getLatestRelease(repoFullName, UpdateChannel.STABLE);
    }

    public ReleaseAssets getLatestRelease(String repoFullName, UpdateChannel channel)
        throws IOException, InterruptedException {
        String[] parts = repoFullName.split("/");
        if (parts.length != 2) {
            return new ReleaseAssets(List.of(), List.of());
        }
        String owner = parts[0];
        String repo = parts[1];

        if (UpdateChannel.from(channel == null ? null : channel.id()) == UpdateChannel.BETA) {
            String url = apiBase + "/repos/" + owner + "/" + repo + "/releases?per_page=20";
            return fetchReleaseList(url, "No releases found for this repository.");
        }
        String url = apiBase + "/repos/" + owner + "/" + repo + "/releases/latest";
        return fetchRelease(url, "No releases found for this repository.");
    }

    // one release by tag
    public ReleaseAssets getRelease(String repoFullName, String tag)
        throws IOException, InterruptedException {
        String[] parts = repoFullName.split("/");
        if (parts.length != 2) {
            return new ReleaseAssets(List.of(), List.of());
        }
        String owner = parts[0];
        String repo = parts[1];
        String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8)
            .replace("+", "%20");
        String url = apiBase + "/repos/" + owner + "/" + repo
            + "/releases/tags/" + encodedTag;
        return fetchRelease(url, "Release " + tag + " was not found.");
    }

    private ReleaseAssets fetchRelease(String url, String emptyMessage)
        throws IOException, InterruptedException {
        var jsonResponse = request(URI.create(url));
        if (jsonResponse.isEmpty()) {
            Output.error(emptyMessage);
            return new ReleaseAssets(List.of(), List.of());
        }
        ObjectMapper mapper = new ObjectMapper();
        Release release = mapper.readValue(jsonResponse.get(), Release.class);
        return toReleaseAssets(release);
    }

    private ReleaseAssets fetchReleaseList(String url, String emptyMessage)
        throws IOException, InterruptedException {
        var jsonResponse = request(URI.create(url));
        if (jsonResponse.isEmpty()) {
            Output.error(emptyMessage);
            return new ReleaseAssets(List.of(), List.of());
        }
        ObjectMapper mapper = new ObjectMapper();
        List<Release> releases = mapper.readValue(
            jsonResponse.get(), new TypeReference<List<Release>>() {});
        Release release = releases.stream()
            .filter(candidate -> !candidate.draft())
            .findFirst()
            .orElse(null);
        if (release == null) {
            Output.error(emptyMessage);
            return new ReleaseAssets(List.of(), List.of());
        }
        return toReleaseAssets(release);
    }

    private ReleaseAssets toReleaseAssets(Release release) {
        List<Asset> all = release.assets() == null ? List.of() : release.assets();
        List<Asset> installable = filterInstallable(all);
        return new ReleaseAssets(release.tagName(), release.id(), all, installable);
    }

    // downloads to a temp file; the caller owns the file until it is installed
    public Path downloadAsset(Asset asset) throws IOException, InterruptedException {
        Path destination = StagedAsset.create(asset.name());
        try {
            downloadToFile(asset.url(), destination);
            StagedAsset.requireContent(destination, asset.name());
            return destination;
        } catch (IOException | InterruptedException | RuntimeException e) {
            StagedAsset.discard(destination);
            throw e;
        }
    }

    private void downloadToFile(String url, Path destination) throws IOException, InterruptedException {
        HttpRequest request = authorized(HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", ACCEPT_STREAM_HEADER)
            .timeout(downloadTimeout()))
            .build();
        URI uri = request.uri();
        CompletableFuture<HttpResponse<Path>> future =
            client().sendAsync(request, HttpResponse.BodyHandlers.ofFile(destination));
        try {
            HttpResponse<Path> response = future.get(
                downloadTimeout().toMillis(), TimeUnit.MILLISECONDS);
            handleResponseCode(response);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ProviderException(
                ProviderException.Reason.TIMEOUT,
                "download timed out after " + downloadTimeout().toSeconds() + "s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw networkError(io, uri, "download");
            }
            if (cause instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new IOException("Download failed: " + cause, cause);
        }
    }

    // adds the bearer token when one is configured
    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    // GET as JSON; 4xx is turned into an IOException
    private Optional<String> request(URI uri) throws IOException, InterruptedException {
        HttpRequest request = authorized(HttpRequest.newBuilder()
            .uri(uri)
            .header("Accept", ACCEPT_JSON_HEADER)
            .timeout(NetworkSettings.apiTimeout()))
            .build();
        HttpResponse<String> response;
        try {
            response = client().send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
        } catch (IOException e) {
            throw networkError(e, uri, "request");
        }
        handleResponseCode(response);
        return Optional.of(response.body());
    }

    private static IOException networkError(
        IOException error, URI uri, String action) {
        if (error instanceof HttpTimeoutException) {
            Duration timeout = "download".equals(action)
                ? NetworkSettings.downloadTimeout() : NetworkSettings.apiTimeout();
            return new ProviderException(
                ProviderException.Reason.TIMEOUT,
                action + " timed out after " + timeout.toSeconds() + "s", error);
        }
        if (error instanceof UnknownHostException) {
            return new ProviderException(
                ProviderException.Reason.UNREACHABLE,
                "could not resolve " + uri.getHost(), error);
        }
        if (error instanceof ConnectException) {
            return new ProviderException(
                ProviderException.Reason.UNREACHABLE,
                "could not connect to " + uri.getHost(), error);
        }
        return error;
    }

    // 403 is a rate limit only when GitHub says the remaining quota is zero
    private void handleResponseCode(HttpResponse<?> response) throws IOException {
        int statusCode = response.statusCode();
        if (statusCode >= 400) {
            String body = errorBody(response);
            switch (statusCode) {
                case 403 -> {
                    if (response.headers()
                        .firstValueAsLong("X-RateLimit-Remaining").orElse(-1) == 0) {
                        throw new ProviderException(
                            ProviderException.Reason.RATE_LIMITED,
                            rateLimitMessage(response.headers()));
                    }
                    if (body.toLowerCase(java.util.Locale.ROOT).contains("rate limit")) {
                        throw new ProviderException(
                            ProviderException.Reason.RATE_LIMITED,
                            "Rate limit exceeded; try again later.");
                    }
                    throw new ProviderException(
                        ProviderException.Reason.UNAUTHORIZED, "access denied");
                }
                case 401 -> throw new ProviderException(
                    ProviderException.Reason.UNAUTHORIZED, "authentication failed");
                case 404 -> throw new ProviderException(
                    ProviderException.Reason.NOT_FOUND, "resource not found");
                case 429 -> throw new ProviderException(
                    ProviderException.Reason.RATE_LIMITED,
                    "Rate limit exceeded; try again later.");
                default -> throw new IOException("HTTP Error " + statusCode + ": " + body);
            }
        }
    }

    private static String errorBody(HttpResponse<?> response) {
        Object body = response.body();
        if (body instanceof Path path) {
            try {
                if (Files.size(path) < 8192) {
                    return Files.readString(path, StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
                // no error body available
            }
            return "";
        }
        return body == null ? "" : body.toString();
    }

    // logs the quota reset time so the user knows when to retry
    private static String rateLimitMessage(HttpHeaders headers) {
        long resetTime = headers.firstValueAsLong("X-RateLimit-Reset").orElse(0);
        String suffix = resetTime > 0
            ? String.format(" after %tT UTC", new Date(resetTime * 1000))
            : " later";
        return "Rate limit exceeded; try again" + suffix + ".";
    }

    // keep assets for this platform and architecture
    private List<Asset> filterInstallable(List<Asset> assets) {
        return AssetSelector.installable(
            assets,
            platformHandler.getFormats(),
            Platform.detectPlatform().getArchitecture());
    }

    // parse the search response
    private SearchResult getSearchResult(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, SearchResult.class);
    }
}
