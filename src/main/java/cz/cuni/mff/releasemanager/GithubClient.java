package cz.cuni.mff.releasemanager;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;

import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.Release;
import cz.cuni.mff.releasemanager.types.SearchResult;
import cz.cuni.mff.releasemanager.utils.FileUtils;

/**
 * This class is responsible for interacting with the Github API.
 * It provides methods to search for repositories, get the latest release assets,
 * and install those assets.
 */
public class GithubClient {

    private static final String API_URL = "https://api.github.com";
    private static final String ACCEPT_JSON_HEADER = "application/vnd.github.v3+json";
    private static final String ACCEPT_STREAM_HEADER = "application/octet-stream";
    private static final String RESULT_COUNT = "5";
    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private final HttpClient client;
    private final PlatformHandler platformHandler;
    private final Duration downloadTimeout;

    /**
     * Constructor for GithubClient.
     * Initializes the HttpClient and PlatformHandler.
     */
    public GithubClient() {
        this(DEFAULT_DOWNLOAD_TIMEOUT);
    }

    GithubClient(Duration downloadTimeout) {
        this.downloadTimeout = downloadTimeout;
        client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        platformHandler = Platform.getPlatformHandler();
    }

    /**
     * Searches for repositories by name using the Github API.
     * @param name The name of the repository to search for.
     * @return An Optional containing the SearchResult if found, otherwise empty.
     */
    public Optional<SearchResult> searchRepoByName(String name) {
        String query = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String url = API_URL + "/search/repositories?q=" + query + "&per_page=" + RESULT_COUNT;
        try {
            var jsonResponse = request(URI.create(url));
            if (!jsonResponse.isPresent()) {
                return Optional.empty();
            }
            String json = jsonResponse.get();
            SearchResult searchResult = getSearchResult(json);
            return Optional.ofNullable(searchResult);

        } catch (IOException | InterruptedException ex) {
            System.err.println(ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * All assets in a release, plus the subset that can be installed on this platform.
     */
    public record ReleaseAssets(List<Asset> all, List<Asset> installable) {}

    /**
     * Retrieves the latest release assets for a given repository.
     * @param repoFullName The full name of the repository (owner/repo).
     * @return A list of installable Asset objects.
     */
    public List<Asset> getLatestReleaseAssets(String repoFullName) {
        return getLatestRelease(repoFullName).installable();
    }

    /**
     * Retrieves all latest release assets, including checksums and signatures.
     * @param repoFullName The full name of the repository (owner/repo).
     * @return all assets and the installable subset
     */
    public ReleaseAssets getLatestRelease(String repoFullName) {
        String[] parts = repoFullName.split("/");
        if (parts.length != 2) {
            return new ReleaseAssets(List.of(), List.of());
        }
        String owner = parts[0];
        String repo = parts[1];

        String url = API_URL + "/repos/" + owner + "/" + repo + "/releases/latest";
        try {
            var jsonResponse = request(URI.create(url));
            if (jsonResponse.isEmpty()) {
                System.err.println("No releases found for this repository.");
                return new ReleaseAssets(List.of(), List.of());
            }
            ObjectMapper mapper = new ObjectMapper();
            Release release = mapper.readValue(jsonResponse.get(), Release.class);
            List<Asset> all = release.assets() == null ? List.of() : release.assets();
            List<Asset> installable = filterInstallable(all);
            if (installable.isEmpty()) {
                System.err.println("No suitable asset found for this repository.");
            }
            return new ReleaseAssets(all, installable);
        } catch (IOException | InterruptedException ex) {
            System.err.println(ex.getMessage());
            return new ReleaseAssets(List.of(), List.of());
        }
    }

    /**
     * Downloads the asset to a temporary file without installing it.
     * @param asset The asset to download.
     * @return The path to the downloaded file.
     * @throws IOException if the download fails.
     * @throws InterruptedException if the download is interrupted.
     */
    public Path downloadAsset(Asset asset) throws IOException, InterruptedException {
        Path destination = FileUtils.createTempFilePath(asset.name());
        try {
            downloadToFile(asset.url(), destination);
            if (Files.size(destination) == 0) {
                throw new IOException("Downloaded file is empty: " + asset.name());
            }
            return destination;
        } catch (IOException | InterruptedException | RuntimeException e) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            FileUtils.removeTempDir(destination);
            throw e;
        }
    }

    private void downloadToFile(String url, Path destination) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", ACCEPT_STREAM_HEADER)
            .timeout(downloadTimeout)
            .build();
        CompletableFuture<HttpResponse<Path>> future =
            client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(destination));
        try {
            HttpResponse<Path> response = future.get(
                downloadTimeout.toMillis(), TimeUnit.MILLISECONDS);
            handleResponseCode(response);
        } catch (TimeoutException e) {
            future.cancel(true);
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            throw new IOException(
                "Download timed out after " + downloadTimeout.toMillis() + " ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new IOException("Download failed: " + cause, cause);
        }
    }

    /**
     * Installs an already downloaded asset and removes the staged file afterwards.
     * @param assetPath The downloaded asset.
     * @param repoFullName Repository in owner/name form.
     * @return The path to the file for uninstall.
     */
    public Path installDownloadedAsset(Path assetPath, String repoFullName) {
        if (assetPath == null) {
            return null;
        }
        Path installed = null;
        try {
            installed = platformHandler.install(assetPath, repoFullName);
            return installed;
        } finally {
            if (!assetPath.equals(installed)) {
                discardAsset(assetPath);
            }
        }
    }

    /**
     * Removes a staged download that will not be installed.
     * @param assetPath The downloaded asset.
     */
    public void discardAsset(Path assetPath) {
        if (assetPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(assetPath);
        } catch (IOException ignored) {
            // best-effort cleanup of the staged download
        }
        FileUtils.removeTempDir(assetPath);
    }

    /**
     * Installs the asset by downloading it and passing it to the platform handler for installation.
     * @param asset The asset to install.
     * @param repoFullName Repository in owner/name form.
     * @return The path to the file for uninstall.
     */
    public Path installAsset(Asset asset, String repoFullName) {
        Path assetPath;
        try {
            assetPath = downloadAsset(asset);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("Error downloading asset: " + e.getMessage());
            return null;
        }
        return installDownloadedAsset(assetPath, repoFullName);
    }

    /**
     * Requests the given URI and returns the response body as a string.
     * @param uri The URI to request.
     * @return Optional containing the response body as a string.
     * @throws IOException
     * @throws InterruptedException
     */
    private Optional<String> request(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(uri)
            .header("Accept", ACCEPT_JSON_HEADER)
            .timeout(API_TIMEOUT)
            .build();
        HttpResponse<String> response;
        try {
            response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
        } catch (ConnectException e) {
            throw new IOException("Connection error.", e);
        }
        handleResponseCode(response);
        return Optional.of(response.body());
    }

    /**
     * Handles the response code from the HTTP request. Used for logging.
     * @param response The HTTP response.
     * @throws IOException
     */
    private void handleResponseCode(HttpResponse<?> response) throws IOException {
        int statusCode = response.statusCode();
        if (statusCode >= 400) {
            String body = errorBody(response);
            switch (statusCode) {
                case 403 -> {
                    handleRateLimit(response.headers());
                    throw new IOException("Rate limit exceeded. Try again later.");
                }
                case 404 -> throw new IOException("Resource not found: " + body);
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

    /**
     * Handles the rate limit response from the Github API. Used for logging.
     * @param headers The HTTP headers from the response.
     */
    private void handleRateLimit(HttpHeaders headers) {
        long remaining = headers.firstValueAsLong("X-RateLimit-Remaining").orElse(0);
        long resetTime = headers.firstValueAsLong("X-RateLimit-Reset").orElse(0);

        System.err.printf("Rate limit exceeded: Remaining: %d, Reset: %tT UTC%n",
            remaining, new Date(resetTime * 1000));
    }

    /**
     * Finds suitable assets in the JSON response from the Github API.
     * @param json The JSON response.
     * @return A list of Asset objects.
     * @throws IOException
     */
    private List<Asset> filterInstallable(List<Asset> assets) {
        String[] formats = platformHandler.getFormats();
        return assets.stream()
            .filter(asset -> asset != null && asset.name() != null)
            .filter(asset -> {
                String name = asset.name().toLowerCase(Locale.ROOT);
                for (String format : formats) {
                    if (name.endsWith(format)) {
                        return true;
                    }
                }
                return false;
            })
            .toList();
    }

    /**
     * Converts the JSON string to a SearchResult object.
     * @param json The JSON string.
     * @return The SearchResult object.
     * @throws IOException
     */
    private SearchResult getSearchResult(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, SearchResult.class);
    }
}
