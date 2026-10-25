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
 * GitLab API v4 provider.
 */
public final class GitLabProvider implements ReleaseProvider {
    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderConfig config;
    private final HttpClient client;

    public GitLabProvider(ProviderConfig config) {
        this(config, HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(30))
            .build());
    }

    GitLabProvider(ProviderConfig config, HttpClient client) {
        this.config = config;
        this.client = client;
    }

    @Override
    public ProviderConfig config() {
        return config;
    }

    @Override
    public ReleaseAssets latest(String path) throws IOException, InterruptedException {
        String project = URLEncoder.encode(path, StandardCharsets.UTF_8);
        String url = config.resolvedApi() + "/projects/" + project + "/releases?per_page=20";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("User-Agent", "ape-get")
            .timeout(API_TIMEOUT)
            .GET();
        token().ifPresent(value -> builder.header("PRIVATE-TOKEN", value));

        HttpResponse<String> response = client.send(
            builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("GitLab authentication failed for " + config.name());
        }
        if (response.statusCode() != 200) {
            throw new IOException(
                "GitLab API returned " + response.statusCode() + " for " + path);
        }

        List<GitLabRelease> releases = MAPPER.readValue(
            response.body(), new TypeReference<List<GitLabRelease>>() {});
        GitLabRelease release = releases.stream()
            .filter(candidate -> !candidate.upcoming())
            .findFirst()
            .orElseThrow(() -> new IOException("No published release found for " + path));
        return toReleaseAssets(release);
    }

    @Override
    public ReleaseAssets release(String path, String tag)
        throws IOException, InterruptedException {
        String project = URLEncoder.encode(path, StandardCharsets.UTF_8);
        String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8)
            .replace("+", "%20");
        String url = config.resolvedApi() + "/projects/" + project
            + "/releases/" + encodedTag;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("User-Agent", "ape-get")
            .timeout(API_TIMEOUT)
            .GET();
        token().ifPresent(value -> builder.header("PRIVATE-TOKEN", value));

        HttpResponse<String> response = client.send(
            builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("GitLab authentication failed for " + config.name());
        }
        if (response.statusCode() != 200) {
            throw new IOException(
                "GitLab API returned " + response.statusCode() + " for " + path
                    + " release " + tag);
        }
        GitLabRelease release = MAPPER.readValue(response.body(), GitLabRelease.class);
        if (release.upcoming()) {
            throw new IOException("Release " + tag + " is not published yet");
        }
        return toReleaseAssets(release);
    }

    private static ReleaseAssets toReleaseAssets(GitLabRelease release) {
        List<Asset> all = release.assets() == null || release.assets().links() == null
            ? List.of()
            : release.assets().links().stream()
                .filter(link -> link.name() != null && downloadUrl(link) != null)
                .map(link -> new Asset(downloadUrl(link), link.name()))
                .toList();
        List<Asset> installable = AssetSelector.installable(
            all,
            Platform.getPlatformHandler().getFormats(),
            Platform.detectPlatform().getArchitecture());
        return new ReleaseAssets(release.tagName(), null, all, installable);
    }

    @Override
    public Path download(Asset asset) throws IOException, InterruptedException {
        Path destination = FileUtils.createTempFilePath(asset.name());
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(asset.url()))
                .header("User-Agent", "ape-get")
                .timeout(DOWNLOAD_TIMEOUT)
                .GET();
            token().ifPresent(value -> builder.header("PRIVATE-TOKEN", value));

            HttpResponse<Path> response = client.send(
                builder.build(), HttpResponse.BodyHandlers.ofFile(destination));
            if (response.statusCode() != 200) {
                throw new IOException("GitLab download returned " + response.statusCode()
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
        String url = config.resolvedApi() + "/projects?search="
            + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&per_page=5&simple=true";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("User-Agent", "ape-get")
            .timeout(API_TIMEOUT)
            .GET();
        token().ifPresent(value -> builder.header("PRIVATE-TOKEN", value));

        HttpResponse<String> response = client.send(
            builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(
                "GitLab search returned " + response.statusCode());
        }
        List<GitLabProject> projects = MAPPER.readValue(
            response.body(), new TypeReference<List<GitLabProject>>() {});
        List<Repo> repos = projects.stream()
            .filter(project -> project.pathWithNamespace() != null)
            .map(project -> new Repo(
                project.pathWithNamespace(), project.description()))
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

    private static String downloadUrl(GitLabLink link) {
        if (link.directAssetUrl() != null && !link.directAssetUrl().isBlank()) {
            return link.directAssetUrl();
        }
        return link.url() == null || link.url().isBlank() ? null : link.url();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitLabProject(
        @JsonProperty("path_with_namespace") String pathWithNamespace,
        String description
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitLabRelease(
        @JsonProperty("tag_name") String tagName,
        @JsonProperty("released_at") String releasedAt,
        @JsonProperty("upcoming_release") boolean upcoming,
        GitLabAssets assets
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitLabAssets(List<GitLabLink> links) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitLabLink(
        String name,
        String url,
        @JsonProperty("direct_asset_url") String directAssetUrl
    ) {}
}
