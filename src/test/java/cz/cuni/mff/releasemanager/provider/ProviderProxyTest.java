package cz.cuni.mff.releasemanager.provider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderProxyTest {

    @Test
    @Timeout(15)
    void routesHttpProvidersThroughTheConfiguredProxy() throws Exception {
        AtomicBoolean hit = new AtomicBoolean();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            hit.set(true);
            byte[] body = """
                [{"id": 7, "tag_name": "v1.0.0", "draft": false,
                  "prerelease": false, "assets": []}]
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        proxy.start();
        NetworkSettings.configure(
            5, "http://127.0.0.1:" + proxy.getAddress().getPort());
        try {
            ProviderConfig config = new ProviderConfig(
                "work", "gitea", "http://unreachable.invalid", null, null);

            ReleaseAssets release = new GiteaProvider(config).latest("owner/repo");

            assertEquals("v1.0.0", release.version());
            assertTrue(hit.get(), "the request never reached the proxy");
        } finally {
            NetworkSettings.configure(0, null);
            proxy.stop(0);
        }
    }
}
