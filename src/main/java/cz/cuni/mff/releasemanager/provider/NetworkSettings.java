package cz.cuni.mff.releasemanager.provider;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Process-wide network settings loaded from the tool config.
 */
public final class NetworkSettings {
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private static final Duration MIN_DOWNLOAD = Duration.ofMinutes(5);
    private static final Duration MIN_RNGIT_METADATA = Duration.ofMinutes(2);
    private static final Duration MIN_RNGIT_FETCH = Duration.ofMinutes(30);

    private static volatile Duration apiTimeout =
        Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
    private static volatile InetSocketAddress proxy;

    private NetworkSettings() {}

    /**
     * Applies a config snapshot.
     * @param timeoutSeconds API and connect timeout; values below one use the default
     * @param proxyValue HTTP proxy URL, or null for direct connections
     * @throws IllegalArgumentException if the proxy URL is unusable
     */
    public static void configure(int timeoutSeconds, String proxyValue) {
        InetSocketAddress parsedProxy = parseProxy(proxyValue);
        apiTimeout = Duration.ofSeconds(timeoutSeconds < 1
            ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds);
        proxy = parsedProxy;
    }

    public static Duration apiTimeout() {
        return apiTimeout;
    }

    public static Duration downloadTimeout() {
        return atLeast(apiTimeout, MIN_DOWNLOAD);
    }

    // Reticulum path discovery and link setup are slower than an HTTP request
    public static Duration rngitMetadataTimeout() {
        return atLeast(apiTimeout, MIN_RNGIT_METADATA);
    }

    public static Duration rngitFetchTimeout() {
        return atLeast(apiTimeout, MIN_RNGIT_FETCH);
    }

    public static Optional<InetSocketAddress> proxy() {
        return Optional.ofNullable(proxy);
    }

    public static void applyProxy(HttpClient.Builder builder) {
        InetSocketAddress address = proxy;
        if (address != null) {
            builder.proxy(ProxySelector.of(address));
        }
    }

    public static void apply(HttpClient.Builder builder) {
        applyProxy(builder);
        builder.connectTimeout(apiTimeout);
    }

    static InetSocketAddress parseProxy(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String raw = value.strip();
        if (!raw.contains("://")) {
            raw = "http://" + raw;
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid proxy URL: " + value, e);
        }
        String scheme = uri.getScheme() == null
            ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.startsWith("socks")) {
            throw new IllegalArgumentException(
                "SOCKS proxies are not supported by the HTTP client;"
                    + " use Tor's HTTPTunnelPort with an http:// proxy.");
        }
        if (!"http".equals(scheme)) {
            throw new IllegalArgumentException(
                "Proxy must be http://host:port, got " + scheme + "://");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid proxy URL: " + value);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                "Proxy credentials in the URL are not supported.");
        }
        int port = uri.getPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                "Proxy URL must include a port: " + value);
        }
        return InetSocketAddress.createUnresolved(uri.getHost(), port);
    }

    private static Duration atLeast(Duration value, Duration floor) {
        return value.compareTo(floor) < 0 ? floor : value;
    }
}
