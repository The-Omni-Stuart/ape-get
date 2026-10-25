package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import cz.cuni.mff.releasemanager.platform.AssetSelector;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.SearchResult;
import cz.cuni.mff.releasemanager.utils.FileUtils;

/**
 * Gitea/Forgejo/Codeberg API provider.
 */
public final class GiteaProvider implements ReleaseProvider {
    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderConfig config;
    private final HttpClient client;

    public GiteaProvider(ProviderConfig config) {
        this(config, HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(30))
            .build());
    }

    GiteaProvider(ProviderConfig config, HttpClient client) {
        this.config = config;
        this.client = client;
    }

    @Override
    public ProviderConfig config() {
        return config;
    }

    @Override
    public ReleaseAssets latest(String path) throws IOException, InterruptedException {
        String[] parts = path.split("/");
        if (parts.length != 2) {
            throw new IOException("Gitea supports owner/repo paths, got: " + path);
        }
        String url = config.resolvedApi() + "/repos/" + parts[0] + "/" + parts[1]
            + "/releases?limit=20";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("User-Agent", "ape-get")
            .timeout(API_TIMEOUT)
            .GET();
        token().ifPresent(value -> builder.header("Authorization", "token " + value));

        HttpResponse<String> response = client.send(
            builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("Gitea authentication failed for " + config.name());
        }
        if (response.statusCode() != 200) {
            throw new IOException(
                "Gitea API returned " + response.statusCode() + " for " + path);
        }

        List<GiteaRelease> releases = MAPPER.readValue(
            response.body(), new TypeReference<List<GiteaRelease>>() {});
        GiteaRelease release = releases.stream()
            .filter(candidate -> !candidate.draft() && !candidate.prerelease())
            .findFirst()
            .orElseThrow(() -> new IOException("No published release found for " + path));

        List<Asset> all = release.assets() == null
            ? List.of()
            : release.assets().stream()
                .filter(asset -> asset.name() != null
                    && asset.downloadUrl() != null
                    && !asset.downloadUrl().isBlank())
                .map(asset -> new Asset(
                    resolveDownloadUrl(asset.downloadUrl()), asset.name()))
                .toList();
        List<Asset> installable = AssetSelector.installable(
            all,
            Platform.getPlatformHandler().getFormats(),
            Platform.detectPlatform().getArchitecture());
        return new ReleaseAssets(release.tagName(), release.id(), all, installable);
    }

    @Override
    public Path download(Asset asset) throws IOException, InterruptedException {
        Path destination = FileUtils.createTempFilePath(asset.name());
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(asset.url()))
                .header("User-Agent", "ape-get")
                .timeout(DOWNLOAD_TIMEOUT)
                .GET();
            token().ifPresent(value -> builder.header("Authorization", "token " + value));

            HttpResponse<Path> response = client.send(
                builder.build(), HttpResponse.BodyHandlers.ofFile(destination));
            if (response.statusCode() != 200) {
                throw new IOException(
                    "Gitea download returned " + response.statusCode()
                        + " for " + asset.name());
            }
            if (Files.size(destination) == 0) {
                throw new IOException("Downloaded file is empty: " + asset.name());
            }
            return destination.toAbsolutePath();
        } catch (IOException | InterruptedException e) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
                // preserve the original failure
            }
            FileUtils.removeTempDir(destination);
            throw e;
        }
    }

    @Override
    public SearchResult search(String query) throws IOException, InterruptedException {
        String url = config.resolvedApi() + "/repos/search?q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=5";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("User-Agent", "ape-get")
            .timeout(API_TIMEOUT)
            .GET();
        token().ifPresent(value -> builder.header("Authorization", "token " + value));

        HttpResponse<String> response = client.send(
            builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(
                "Gitea search returned " + response.statusCode());
        }
        GiteaSearch result = MAPPER.readValue(response.body(), GiteaSearch.class);
        List<Repo> repos = result.data() == null
            ? List.of()
            : result.data().stream()
                .filter(repo -> repo.fullName() != null)
                .map(repo -> new Repo(repo.fullName(), repo.description()))
                .toList();
        return new SearchResult(repos);
    }

    @Override
    public void discardAsset(Path assetPath) {
        if (assetPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(assetPath);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
        FileUtils.removeTempDir(assetPath);
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

    private String resolveDownloadUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        String base = config.url().endsWith("/") ? config.url() : config.url() + "/";
        return URI.create(base).resolve(url).toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GiteaRelease(
        Long id,
        @JsonProperty("tag_name") String tagName,
        boolean draft,
        boolean prerelease,
        List<GiteaAsset> assets
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GiteaSearch(List<GiteaRepo> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GiteaRepo(
        @JsonProperty("full_name") String fullName,
        String description
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GiteaAsset(
        String name,
        @JsonProperty("browser_download_url") String downloadUrl
    ) {}
}
