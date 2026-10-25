package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import cz.cuni.mff.releasemanager.platform.AssetSelector;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.SearchResult;

// Gitea, Forgejo, and Codeberg API v1 provider
public final class GiteaProvider implements ReleaseProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderConfig config;
    private final ProviderHttp http;

    public GiteaProvider(ProviderConfig config) {
        this(config, client());
    }

    private static HttpClient client() {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_2);
        NetworkSettings.apply(builder);
        return builder.build();
    }

    GiteaProvider(ProviderConfig config, HttpClient client) {
        this.config = config;
        this.http = new ProviderHttp(
            config, client, "Gitea", "Authorization", "token ");
    }

    @Override
    public ProviderConfig config() {
        return config;
    }

    @Override
    public ReleaseAssets latest(String path) throws IOException, InterruptedException {
        return latest(path, UpdateChannel.STABLE);
    }

    @Override
    public ReleaseAssets latest(String path, UpdateChannel channel)
        throws IOException, InterruptedException {
        String[] parts = requireRepo(path);
        String url = config.resolvedApi() + "/repos/" + parts[0] + "/" + parts[1]
            + "/releases?limit=20";
        List<GiteaRelease> releases = MAPPER.readValue(
            http.get(url, path), new TypeReference<List<GiteaRelease>>() {});
        boolean beta = UpdateChannel.from(channel == null ? null : channel.id())
            == UpdateChannel.BETA;
        GiteaRelease release = releases.stream()
            .filter(candidate -> !candidate.draft())
            .filter(candidate -> beta || !candidate.prerelease())
            .findFirst()
            .orElseThrow(() -> new IOException("No published release found for " + path));
        return toReleaseAssets(release);
    }

    @Override
    public ReleaseAssets release(String path, String tag)
        throws IOException, InterruptedException {
        String[] parts = requireRepo(path);
        String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8)
            .replace("+", "%20");
        String url = config.resolvedApi() + "/repos/" + parts[0] + "/" + parts[1]
            + "/releases/tags/" + encodedTag;
        GiteaRelease release = MAPPER.readValue(
            http.get(url, path + " release " + tag), GiteaRelease.class);
        if (release.draft()) {
            throw new IOException("Release " + tag + " is a draft");
        }
        return toReleaseAssets(release);
    }

    private static String[] requireRepo(String path) throws IOException {
        String[] parts = path.split("/");
        if (parts.length != 2) {
            throw new IOException("Gitea supports owner/repo paths, got: " + path);
        }
        return parts;
    }

    private ReleaseAssets toReleaseAssets(GiteaRelease release) {
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
        return http.download(asset);
    }

    @Override
    public SearchResult search(String query) throws IOException, InterruptedException {
        String url = config.resolvedApi() + "/repos/search?q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=5";
        GiteaSearch result = MAPPER.readValue(
            http.get(url, "search"), GiteaSearch.class);
        List<Repo> repos = result.data() == null
            ? List.of()
            : result.data().stream()
                .filter(repo -> repo.fullName() != null)
                .map(repo -> new Repo(repo.fullName(), repo.description()))
                .toList();
        return new SearchResult(repos);
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
