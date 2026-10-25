package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderDetectorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void detectsRngitDestinationsWithoutProbing() throws Exception {
        ProviderDetector.Detected detected = new ProviderDetector(
            HttpClient.newHttpClient())
            .detect("rns://7649a50d84610232d1416b41d2896aff");

        assertEquals(ProviderType.RNGIT, detected.type());
        assertEquals("rns://7649a50d84610232d1416b41d2896aff", detected.api());
    }

    @Test
    void detectsGitea() throws Exception {
        String base = start(200, 404, 404);

        ProviderDetector.Detected detected =
            new ProviderDetector(HttpClient.newHttpClient()).detect(base);

        assertEquals(ProviderType.GITEA, detected.type());
        assertEquals(base + "/api/v1", detected.api());
    }

    @Test
    void detectsGitlab() throws Exception {
        String base = start(404, 200, 404);

        ProviderDetector.Detected detected =
            new ProviderDetector(HttpClient.newHttpClient()).detect(base);

        assertEquals(ProviderType.GITLAB, detected.type());
        assertEquals(base + "/api/v4", detected.api());
    }

    @Test
    void detectsGitHubEnterprise() throws Exception {
        String base = start(404, 404, 200);

        ProviderDetector.Detected detected =
            new ProviderDetector(HttpClient.newHttpClient()).detect(base);

        assertEquals(ProviderType.GITHUB, detected.type());
        assertEquals(base + "/api/v3", detected.api());
    }

    @Test
    void refusesToGuessWhenSeveralApisRespond() throws Exception {
        String base = start(200, 200, 404);

        assertThrows(IOException.class,
            () -> new ProviderDetector(HttpClient.newHttpClient()).detect(base));
    }

    @Test
    void reportsNothingDetected() throws Exception {
        String base = start(404, 404, 404);

        assertThrows(IOException.class,
            () -> new ProviderDetector(HttpClient.newHttpClient()).detect(base));
    }

    @Test
    void rejectsAMalformedUrlWithAClearMessage() {
        IOException error = assertThrows(IOException.class,
            () -> new ProviderDetector(HttpClient.newHttpClient())
                .detect("https://exa mple.com"));

        assertTrue(error.getMessage().contains("Invalid provider URL"),
            error.getMessage());
    }

    @Test
    void knowsBuiltInHostsWithoutProbing() throws Exception {
        ProviderDetector detector = new ProviderDetector(HttpClient.newHttpClient());

        assertEquals(ProviderType.GITEA,
            detector.detect("https://codeberg.org").type());
        assertEquals(ProviderType.GITLAB,
            detector.detect("https://gitlab.com").type());
        assertEquals(ProviderType.GITHUB,
            detector.detect("https://github.com").type());
    }

    private String start(int gitea, int gitlab, int github) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        context("/api/v1/version", gitea);
        context("/api/v4/metadata", gitlab);
        context("/api/v3/rate_limit", github);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void context(String path, int status) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }
}
