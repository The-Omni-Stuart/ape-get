package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// detects the provider type and API base from an instance URL
public final class ProviderDetector {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public record Detected(ProviderType type, String api) {}

    private final HttpClient client;

    public ProviderDetector() {
        this(HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build());
    }

    ProviderDetector(HttpClient client) {
        this.client = client;
    }

    // probes the instance for one unambiguous API layout
    public Detected detect(String url) throws IOException, InterruptedException {
        String base = stripTrailingSlash(url);
        String host;
        try {
            host = URI.create(base).getHost();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid provider URL: " + url, e);
        }
        if (host == null) {
            throw new IOException("Invalid provider URL: " + url);
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("github.com")) {
            return new Detected(ProviderType.GITHUB, "https://api.github.com");
        }
        if (normalizedHost.equals("codeberg.org")) {
            return new Detected(ProviderType.GITEA, base + "/api/v1");
        }
        if (normalizedHost.equals("gitlab.com")) {
            return new Detected(ProviderType.GITLAB, base + "/api/v4");
        }

        List<Detected> found = new ArrayList<>();
        if (probe(base + "/api/v1/version")) {
            found.add(new Detected(ProviderType.GITEA, base + "/api/v1"));
        }
        if (probe(base + "/api/v4/metadata")) {
            found.add(new Detected(ProviderType.GITLAB, base + "/api/v4"));
        }
        if (probe(base + "/api/v3/rate_limit")) {
            found.add(new Detected(ProviderType.GITHUB, base + "/api/v3"));
        }

        if (found.size() == 1) {
            return found.get(0);
        }
        if (found.size() > 1) {
            StringBuilder message = new StringBuilder(
                "Multiple provider APIs responded at " + base + ": ");
            for (Detected detected : found) {
                message.append(detected.type().id()).append(" (").append(detected.api()).append(") ");
            }
            message.append("- pass --type github|gitea|gitlab");
            throw new IOException(message.toString());
        }
        throw new IOException(
            "Could not detect the provider type at " + base
                + ". Pass --type github|gitea|gitlab, or --api if the API is on another host.");
    }

    private boolean probe(String url) throws InterruptedException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "ape-get")
                .timeout(TIMEOUT)
                .GET()
                .build();
            HttpResponse<Void> response = client.send(
                request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider URL is required");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
