package cz.cuni.mff.releasemanager;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.SearchResult;

class GithubClientTest {

    private GithubClient githubClient;
    private HttpClient mockHttpClient;
    private PlatformHandler mockPlatformHandler;

    @BeforeEach
    void setUp() throws Exception {
        githubClient = new GithubClient();

        // use reflection to set the private fields - replace with dependency injection framework
        mockHttpClient = mock(HttpClient.class);
        Field clientField = GithubClient.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(githubClient, mockHttpClient);

        mockPlatformHandler = mock(PlatformHandler.class);
        Field handlerField = GithubClient.class.getDeclaredField("platformHandler");
        handlerField.setAccessible(true);
        handlerField.set(githubClient, mockPlatformHandler);
    }

    @Test
    void fetchesReleaseByTag() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("""
            { "id": 7, "tag_name": "v1.2.3", "assets": [
              { "name": "app.bin", "browser_download_url": "https://example.invalid/app" }
            ] }
            """);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
            .thenReturn(mockResponse);
        when(mockPlatformHandler.getFormats()).thenReturn(new String[] {".bin"});

        ReleaseAssets release = githubClient.getRelease("owner/repo", "v1.2.3");

        assertEquals("v1.2.3", release.version());
        assertEquals(7L, release.releaseId());
        assertEquals(1, release.all().size());
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
        assertEquals("/repos/owner/repo/releases/tags/v1.2.3",
            captor.getValue().uri().getPath());
    }

    @Test
    void followsRedirectsWithoutAllowingHttpsDowngrade() throws Exception {
        GithubClient client = new GithubClient();
        Field clientField = GithubClient.class.getDeclaredField("client");
        clientField.setAccessible(true);

        HttpClient http = (HttpClient) clientField.get(client);

        assertEquals(HttpClient.Redirect.NORMAL, http.followRedirects());
    }

    @Test
    void sendsGitHubTokenFromEnvironment() throws Exception {
        assumeTrue(System.getenv("PATH") != null);
        GithubClient tokenClient = new GithubClient("https://api.github.com", "PATH");
        Field clientField = GithubClient.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(tokenClient, mockHttpClient);

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"items\":[]}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
            .thenReturn(mockResponse);

        tokenClient.searchRepoByName("token-test");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
        assertEquals("Bearer " + System.getenv("PATH"),
            captor.getValue().headers().firstValue("Authorization").orElse(null));
    }

    @Test
    void searchRepoByName_returnsSearchResultOnSuccess() throws Exception {
        String repoName = "testrepo";
        String json = """
            {
              "items": [
                {
                  "full_name": "owner/testrepo",
                  "description": "Test repository"
                }
              ]
            }
            """;
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        Optional<SearchResult> result = githubClient.searchRepoByName(repoName);

        assertTrue(result.isPresent());
        assertEquals("owner/testrepo", result.get().items().get(0).fullName());
        assertEquals("Test repository", result.get().items().get(0).description());
    }

    @Test
    void getLatestRelease_returnsAssetsOnSuccess() throws Exception {
        String repoFullName = "owner/repo";
        String json = """
            {
              "assets": [
                { "name": "file1.exe", "url": "http://example.com/file1.exe" },
                { "name": "file2.msi", "url": "http://example.com/file2.msi" }
              ]
            }
            """;
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockPlatformHandler.getFormats()).thenReturn(new String[]{"exe", "msi"});
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        List<Asset> assets = githubClient.getLatestRelease(repoFullName).installable();

        assertEquals(2, assets.size());
        assertEquals("file1.exe", assets.get(0).name());
        assertEquals("file2.msi", assets.get(1).name());
        assertEquals("http://example.com/file1.exe", assets.get(0).url());
        assertEquals("http://example.com/file2.msi", assets.get(1).url());
    }

    @Test
    void getLatestRelease_returnsEmptyForInvalidRepoName() {
        List<Asset> assets = githubClient.getLatestRelease("invalidRepoName").installable();
        assertTrue(assets.isEmpty());
    }

    @Test
    void getLatestRelease_exposesAllAndInstallableAssets() throws Exception {
        String json = """
            {
              "assets": [
                { "name": "Example-1.0.AppImage", "url": "http://example.com/Example-1.0.AppImage" },
                { "name": "Example-1.0.AppImage.DIGEST", "url": "http://example.com/digest" },
                { "name": "Example-1.0.AppImage.sig", "url": "http://example.com/sig" },
                { "name": "Example-1.0.AppImage.zsync", "url": "http://example.com/zsync" }
              ]
            }
            """;
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockPlatformHandler.getFormats()).thenReturn(new String[] {".appimage"});
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
            .thenReturn(mockResponse);

        ReleaseAssets release = githubClient.getLatestRelease("owner/repo");

        assertEquals(4, release.all().size());
        assertEquals(1, release.installable().size());
        assertEquals("Example-1.0.AppImage", release.installable().get(0).name());
    }

    @Test
    void getLatestReleasePrefersCurrentArchitecture() throws Exception {
        String json = """
            {
              "assets": [
                { "name": "app-arm64.AppImage", "url": "http://example.com/arm64" },
                { "name": "app-x86_64.AppImage", "url": "http://example.com/x86_64" }
              ]
            }
            """;
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockPlatformHandler.getFormats()).thenReturn(new String[] {".appimage"});
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
            .thenReturn(mockResponse);

        Platform.Architecture architecture = Platform.detectPlatform().getArchitecture();
        assumeTrue(architecture == Platform.Architecture.ARM_64
            || architecture == Platform.Architecture.X86_64);

        ReleaseAssets release = githubClient.getLatestRelease("owner/repo");

        assertEquals(1, release.installable().size());
        assertEquals(architecture == Platform.Architecture.ARM_64
                ? "app-arm64.AppImage"
                : "app-x86_64.AppImage",
            release.installable().get(0).name());
    }

    @Test
    void getLatestRelease_ignoresChecksumAndSignatureFiles() throws Exception {
        String json = """
            {
              "assets": [
                { "name": "Example-1.0.AppImage", "url": "http://example.com/Example-1.0.AppImage" },
                { "name": "Example-1.0.AppImage.DIGEST", "url": "http://example.com/digest" },
                { "name": "Example-1.0.AppImage.sig", "url": "http://example.com/sig" },
                { "name": "Example-1.0.AppImage.zsync", "url": "http://example.com/zsync" }
              ]
            }
            """;
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockPlatformHandler.getFormats()).thenReturn(new String[] {".appimage"});
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
            .thenReturn(mockResponse);

        List<Asset> assets = githubClient.getLatestRelease("owner/repo").installable();

        assertEquals(1, assets.size());
        assertEquals("Example-1.0.AppImage", assets.get(0).name());
    }

    @Test
    void searchRepoByName_urlEncodesQuery() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"items\":[]}");
        when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        githubClient.searchRepoByName("two words & more");

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
        assertEquals("q=two+words+%26+more&per_page=5", requestCaptor.getValue().uri().getRawQuery());
    }

    @Test
    @Timeout(10)
    void downloadAsset_rejectsEmptyResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/empty", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            GithubClient client = new GithubClient(Duration.ofSeconds(5));
            Asset asset = new Asset(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/empty",
                "empty.bin");

            assertThrows(IOException.class, () -> client.downloadAsset(asset));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void downloadAsset_reportsForbiddenWithoutBlamingTheRateLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forbidden", exchange -> {
            byte[] body = "{\"message\":\"Forbidden\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            GithubClient client = new GithubClient(Duration.ofSeconds(5));
            Asset asset = new Asset(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/forbidden",
                "app.bin");

            IOException error = assertThrows(
                IOException.class, () -> client.downloadAsset(asset));

            assertTrue(error.getMessage().contains("403"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void downloadAsset_reportsRateLimitWhenTheHeaderSaysSo() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/limited", exchange -> {
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "0");
            exchange.getResponseHeaders().add("X-RateLimit-Reset", "0");
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            GithubClient client = new GithubClient(Duration.ofSeconds(5));
            Asset asset = new Asset(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/limited",
                "app.bin");

            IOException error = assertThrows(
                IOException.class, () -> client.downloadAsset(asset));

            assertTrue(error.getMessage().contains("Rate limit"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void downloadAsset_timesOutInsteadOfHanging() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(new byte[] {1});
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // test shutdown
            }
            exchange.close();
        });
        server.start();
        try {
            GithubClient client = new GithubClient(Duration.ofMillis(500));
            Asset asset = new Asset(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/slow",
                "slow.bin");

            assertThrows(IOException.class, () -> client.downloadAsset(asset));
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

}