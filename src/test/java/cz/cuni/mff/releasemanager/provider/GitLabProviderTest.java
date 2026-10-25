package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.types.Asset;

class GitLabProviderTest {

    private HttpServer server;
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> privateToken = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(60)
    void readsReleaseAndEncodesSubgroupPath() throws Exception {
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
              { "tag_name": "v2.0", "upcoming_release": true, "assets": { "links": [] } },
              { "tag_name": "v1.1", "upcoming_release": false,
                "assets": { "links": [
                  { "name": "%s", "url": "https://example.invalid/arm",
                    "direct_asset_url": "http://127.0.0.1:0/download/arm" },
                  { "name": "%s", "url": "https://example.invalid/x86",
                    "direct_asset_url": "http://127.0.0.1:0/download/x86" }
                ] } }
            ]
            """;
        startServer("", json.formatted(arm, x86));

        ProviderConfig config = new ProviderConfig(
            "gitlab", "https://gitlab.example", apiBase(), "PATH");
        GitLabProvider provider = new GitLabProvider(config, HttpClient.newHttpClient());

        ReleaseAssets release = provider.latest("group/subgroup/project");

        assertEquals("v1.1", release.version());
        assertEquals(2, release.all().size());
        assertEquals(1, release.installable().size());
        assertEquals(architecture == Platform.Architecture.ARM_64 ? arm : x86,
            release.installable().get(0).name());
        assertTrue(requestedPath.get().contains("group%2Fsubgroup%2Fproject"),
            requestedPath.get());
        assertEquals(System.getenv("PATH"), privateToken.get());
    }

    @Test
    @Timeout(60)
    void readsReleaseByTag() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects", exchange -> {
            requestedPath.set(exchange.getRequestURI().getRawPath());
            byte[] bytes = """
                { "tag_name": "v1.2.3", "upcoming_release": false,
                  "assets": { "links": [
                    { "name": "app.bin", "url": "https://example.invalid/file",
                      "direct_asset_url": "http://127.0.0.1/download/file" }
                  ] } }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        ProviderConfig config = new ProviderConfig(
            "gitlab", "https://gitlab.example", apiBase(), null);
        GitLabProvider provider = new GitLabProvider(config, HttpClient.newHttpClient());

        ReleaseAssets release = provider.release("group/subgroup/project", "v1.2.3");

        assertEquals("v1.2.3", release.version());
        assertEquals(1, release.all().size());
        assertTrue(requestedPath.get().endsWith("/releases/v1.2.3"),
            requestedPath.get());
        assertTrue(requestedPath.get().contains("group%2Fsubgroup%2Fproject"),
            requestedPath.get());
    }

    @Test
    @Timeout(60)
    void searchesProjects() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects", exchange -> {
            byte[] bytes = """
                [ { "path_with_namespace": "group/project", "description": "desc" } ]
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        ProviderConfig config = new ProviderConfig(
            "gitlab", "https://gitlab.example", apiBase(), null);
        GitLabProvider provider = new GitLabProvider(config, HttpClient.newHttpClient());

        var result = provider.search("hello");

        assertEquals(1, result.items().size());
        assertEquals("group/project", result.items().get(0).fullName());
        assertEquals("desc", result.items().get(0).description());
    }

    @Test
    @Timeout(60)
    void downloadsDirectAssetUrl() throws Exception {
        String name = "app.bin";
        String json = """
            [ { "tag_name": "v1", "upcoming_release": false,
                "assets": { "links": [
                  { "name": "%s", "url": "https://example.invalid/file",
                    "direct_asset_url": "http://127.0.0.1:0/download/file" }
                ] } } ]
            """;
        startServer("payload", json.formatted(name));

        ProviderConfig config = new ProviderConfig(
            "gitlab", "https://gitlab.example", apiBase(), null);
        GitLabProvider provider = new GitLabProvider(config, HttpClient.newHttpClient());
        Asset asset = new Asset(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/download/file", name);

        Path downloaded = provider.download(asset);
        try {
            assertEquals("payload", Files.readString(downloaded));
        } finally {
            provider.discardAsset(downloaded);
        }
    }

    private void startServer(String payload, String json) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String body = json.replace("127.0.0.1:0", "127.0.0.1:" + port);
        server.createContext("/api/v4/projects", exchange -> {
            requestedPath.set(exchange.getRequestURI().getRawPath());
            privateToken.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/download/file", exchange -> {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private String apiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4";
    }
}
