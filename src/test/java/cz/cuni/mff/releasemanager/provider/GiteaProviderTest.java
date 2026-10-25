package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.utils.StagedAsset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GiteaProviderTest {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> downloadAuthorization =
        new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void reportsConnectionFailuresWithAReason() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ProviderConfig config = new ProviderConfig(
            "work", "gitea", "http://127.0.0.1:" + port, null, null);
        GiteaProvider provider = new GiteaProvider(config);

        IOException error = assertThrows(IOException.class,
            () -> provider.latest("owner/repo"));

        assertTrue(error.getMessage().contains("could not connect"),
            error.getMessage());
    }

    @Test
    @Timeout(60)
    void readsLatestReleaseAndFiltersArchitecture() throws Exception {
        Platform.Architecture architecture = Platform.detectPlatform().getArchitecture();
        assumeTrue(architecture == Platform.Architecture.ARM_64
            || architecture == Platform.Architecture.X86_64);

        String extension = switch (Platform.detectPlatform().getOS()) {
            case LINUX -> ".AppImage";
            case MAC -> ".dmg";
            case WINDOWS -> ".exe";
        };
        String arm = "app-arm64" + extension;
        String x86 = "app-x86_64" + extension;
        String json = """
            [
              { "id": 1, "tag_name": "v0.9.0", "draft": true, "prerelease": false,
                "assets": [] },
              { "id": 42, "tag_name": "v1.2.3", "draft": false, "prerelease": false,
                "assets": [
                  { "name": "%s", "browser_download_url": "http://127.0.0.1:%d/download/arm" },
                  { "name": "%s", "browser_download_url": "http://127.0.0.1:%d/download/x86" }
                ] }
            ]
            """;
        startServer("", json.formatted(arm, 0, x86, 0));

        ProviderConfig config = new ProviderConfig(
            "gitea", "https://example.invalid", apiBase(), "PATH");
        GiteaProvider provider = new GiteaProvider(config, HttpClient.newHttpClient());

        ReleaseAssets release = provider.latest("owner/repo");

        assertEquals("v1.2.3", release.version());
        assertEquals(42L, release.releaseId());
        assertEquals(2, release.all().size());
        assertEquals(1, release.installable().size());
        assertEquals(architecture == Platform.Architecture.ARM_64 ? arm : x86,
            release.installable().get(0).name());
        assertEquals("token " + System.getenv("PATH"), authorization.get());
    }

    @Test
    @Timeout(60)
    void readsReleaseByTag() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/repos/owner/repo/releases/tags/v1.2.3", exchange -> {
            byte[] bytes = """
                { "id": 42, "tag_name": "v1.2.3", "draft": false, "prerelease": false,
                  "assets": [ { "name": "app.bin",
                    "browser_download_url": "http://127.0.0.1/download/app" } ] }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        ProviderConfig config = new ProviderConfig(
            "gitea", "https://example.invalid", apiBase(), null);
        GiteaProvider provider = new GiteaProvider(config, HttpClient.newHttpClient());

        ReleaseAssets release = provider.release("owner/repo", "v1.2.3");

        assertEquals("v1.2.3", release.version());
        assertEquals(42L, release.releaseId());
        assertEquals(1, release.all().size());
    }

    @Test
    @Timeout(60)
    void searchesRepositories() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/repos/search", exchange -> {
            byte[] bytes = """
                { "data": [ { "full_name": "owner/repo", "description": "desc" } ] }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        ProviderConfig config = new ProviderConfig(
            "gitea", "https://example.invalid", apiBase(), null);
        GiteaProvider provider = new GiteaProvider(config, HttpClient.newHttpClient());

        var result = provider.search("hello");

        assertEquals(1, result.items().size());
        assertEquals("owner/repo", result.items().get(0).fullName());
        assertEquals("desc", result.items().get(0).description());
    }

    @Test
    @Timeout(60)
    void downloadsAssetBytes() throws Exception {
        String name = "app.bin";
        String json = """
            [ { "id": 1, "tag_name": "v1", "draft": false, "prerelease": false,
                "assets": [ { "name": "%s",
                  "browser_download_url": "http://127.0.0.1:%d/download/file" } ] } ]
            """;
        startServer("payload", json.formatted(name, 0));

        ProviderConfig config = new ProviderConfig(
            "gitea", "https://example.invalid", apiBase(), null);
        GiteaProvider provider = new GiteaProvider(config, HttpClient.newHttpClient());
        Asset asset = new Asset(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/download/file", name);

        Path downloaded = provider.download(asset);
        try {
            assertEquals("payload", Files.readString(downloaded));
        } finally {
            StagedAsset.discard(downloaded);
        }
    }

    @Test
    @Timeout(60)
    void sendsTokenToDownloadsFromTheProviderHost() throws Exception {
        startServer("payload", releaseJson("app.bin"));
        ProviderConfig config = new ProviderConfig(
            "gitea", "https://example.invalid", apiBase(), "PATH");
        GiteaProvider provider = new GiteaProvider(config, HttpClient.newHttpClient());
        Asset asset = new Asset(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/download/file", "app.bin");

        Path downloaded = provider.download(asset);
        try {
            assertEquals("token " + System.getenv("PATH"), downloadAuthorization.get());
        } finally {
            StagedAsset.discard(downloaded);
        }
    }

    @Test
    @Timeout(60)
    void doesNotSendTokenToForeignDownloadHosts() throws Exception {
        startServer("payload", releaseJson("app.bin"));
        ProviderConfig config = new ProviderConfig(
            "gitea", "https://example.invalid", apiBase(), "PATH");
        GiteaProvider provider = new GiteaProvider(config, HttpClient.newHttpClient());
        // localhost resolves to the same test server but is not the provider host
        Asset asset = new Asset(
            "http://localhost:" + server.getAddress().getPort() + "/download/file", "app.bin");

        Path downloaded = provider.download(asset);
        try {
            assertEquals("payload", Files.readString(downloaded));
            assertNull(downloadAuthorization.get());
        } finally {
            StagedAsset.discard(downloaded);
        }
    }

    private static String releaseJson(String name) {
        return """
            [ { "id": 1, "tag_name": "v1", "draft": false, "prerelease": false,
                "assets": [ { "name": "%s",
                  "browser_download_url": "http://127.0.0.1:0/download/file" } ] } ]
            """.formatted(name);
    }

    private void startServer(String payload, String json) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        // Replace the placeholder port now that the ephemeral port is known.
        String body = json.replace("127.0.0.1:0", "127.0.0.1:" + port);
        server.createContext("/api/v1/repos/owner/repo/releases", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        for (String path : List.of("/download/arm", "/download/x86", "/download/file")) {
            server.createContext(path, exchange -> {
                downloadAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
        }
        server.start();
    }

    private String apiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }
}
