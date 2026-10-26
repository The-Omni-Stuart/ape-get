package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.Release;
import cz.cuni.mff.releasemanager.types.SearchResult;
import cz.cuni.mff.releasemanager.types.UpdateChannel;
import cz.cuni.mff.releasemanager.utils.Output;

// GitHub API calls and asset downloads
public class GithubClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_API_URL = "https://api.github.com";
    private static final String DEFAULT_TOKEN_ENV = "GRM_GITHUB_TOKEN";
    private static final String ACCEPT_JSON_HEADER = "application/vnd.github.v3+json";
    private static final String RESULT_COUNT = "5";

    private final ProviderHttp http;
    private final Duration downloadTimeout;

    // default github.com endpoint with the standard token variable
    public GithubClient() {
        this(defaultConfig(), null, null);
    }

    public GithubClient(String apiBase, String tokenEnv) {
        this(githubConfig(apiBase, tokenEnv), null, null);
    }

    GithubClient(Duration downloadTimeout) {
        this(defaultConfig(), null, downloadTimeout);
    }

    public GithubClient(ProviderConfig config) {
        this(config, null, null);
    }

    GithubClient(
        ProviderConfig config,
        HttpClient client,
        Duration downloadTimeout) {
        this.http = new ProviderHttp(
            config, client, "GitHub", "Authorization", "Bearer ");
        this.downloadTimeout = downloadTimeout;
    }

    // search repositories by name
    public Optional<SearchResult> searchRepoByName(String name)
        throws IOException, InterruptedException {
        String query = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String url = http.apiBase() + "/search/repositories?q=" + query
            + "&per_page=" + RESULT_COUNT;
        String json = json(url, "search " + name);
        return Optional.ofNullable(MAPPER.readValue(json, SearchResult.class));
    }

    // latest release with all assets
    public ReleaseAssets getLatestRelease(String repoFullName)
        throws IOException, InterruptedException {
        return getLatestRelease(repoFullName, UpdateChannel.STABLE);
    }

    public ReleaseAssets getLatestRelease(String repoFullName, UpdateChannel channel)
        throws IOException, InterruptedException {
        String[] parts = requireRepo(repoFullName);
        String owner = parts[0];
        String repo = parts[1];

        if (UpdateChannel.from(channel == null ? null : channel.id()) == UpdateChannel.BETA) {
            String url = http.apiBase() + "/repos/" + owner + "/" + repo
                + "/releases?per_page=20";
            return fetchReleaseList(url, "No releases found for this repository.");
        }
        String url = http.apiBase() + "/repos/" + owner + "/" + repo
            + "/releases/latest";
        return fetchRelease(url, "No releases found for this repository.");
    }

    // one release by tag
    public ReleaseAssets getRelease(String repoFullName, String tag)
        throws IOException, InterruptedException {
        String[] parts = requireRepo(repoFullName);
        String owner = parts[0];
        String repo = parts[1];
        String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8)
            .replace("+", "%20");
        String url = http.apiBase() + "/repos/" + owner + "/" + repo
            + "/releases/tags/" + encodedTag;
        return fetchRelease(url, "Release " + tag + " was not found.");
    }

    private ReleaseAssets fetchRelease(String url, String emptyMessage)
        throws IOException, InterruptedException {
        String json = json(url, "release");
        if (json == null || json.isBlank()) {
            Output.error(emptyMessage);
            return new ReleaseAssets(List.of());
        }
        Release release = MAPPER.readValue(json, Release.class);
        return toReleaseAssets(release);
    }

    private ReleaseAssets fetchReleaseList(String url, String emptyMessage)
        throws IOException, InterruptedException {
        String json = json(url, "release list");
        if (json == null || json.isBlank()) {
            Output.error(emptyMessage);
            return new ReleaseAssets(List.of());
        }
        List<Release> releases = MAPPER.readValue(
            json, new TypeReference<List<Release>>() {});
        Release release = releases.stream()
            .filter(candidate -> !candidate.draft())
            .findFirst()
            .orElse(null);
        if (release == null) {
            Output.error(emptyMessage);
            return new ReleaseAssets(List.of());
        }
        return toReleaseAssets(release);
    }

    private String json(String url, String context)
        throws IOException, InterruptedException {
        ProviderHttp.Response response = http.send(url, ACCEPT_JSON_HEADER, context);
        handleRateLimit(response);
        http.requireSuccess(response, context);
        return response.body();
    }

    private void handleRateLimit(ProviderHttp.Response response) throws IOException {
        if (response.status() != 403) {
            return;
        }
        if (response.headers()
            .firstValueAsLong("X-RateLimit-Remaining").orElse(-1) == 0) {
            throw new ProviderException(
                ProviderException.Reason.RATE_LIMITED,
                rateLimitMessage(response.headers()));
        }
        if (response.body() != null
            && response.body().toLowerCase(Locale.ROOT).contains("rate limit")) {
            throw new ProviderException(
                ProviderException.Reason.RATE_LIMITED,
                "Rate limit exceeded; try again later.");
        }
    }

    // downloads to a temp file; the caller owns the file until it is installed
    public Path downloadAsset(Asset asset) throws IOException, InterruptedException {
        return http.download(asset, downloadTimeout());
    }

    private Duration downloadTimeout() {
        return downloadTimeout == null
            ? NetworkSettings.downloadTimeout() : downloadTimeout;
    }

    private ReleaseAssets toReleaseAssets(Release release) {
        List<Asset> all = release.assets() == null ? List.of() : release.assets();
        return new ReleaseAssets(release.tagName(), release.id(), all);
    }

    private static String[] requireRepo(String repoFullName) throws IOException {
        String[] parts = repoFullName == null
            ? new String[0] : repoFullName.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new ProviderException(
                ProviderException.Reason.OTHER,
                "GitHub repositories use owner/repo paths, got: " + repoFullName);
        }
        return parts;
    }

    private static String rateLimitMessage(HttpHeaders headers) {
        long resetTime = headers.firstValueAsLong("X-RateLimit-Reset").orElse(0);
        String suffix = resetTime > 0
            ? String.format(" after %tT UTC", new Date(resetTime * 1000))
            : " later";
        return "Rate limit exceeded; try again" + suffix + ".";
    }

    private static ProviderConfig defaultConfig() {
        return new ProviderConfig(
            "github", "github", "https://github.com",
            DEFAULT_API_URL, DEFAULT_TOKEN_ENV);
    }

    private static ProviderConfig githubConfig(String apiBase, String tokenEnv) {
        String url = DEFAULT_API_URL.equals(apiBase) ? "https://github.com" : apiBase;
        return new ProviderConfig("github", "github", url, apiBase, tokenEnv);
    }
}
