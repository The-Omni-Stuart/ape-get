package cz.cuni.mff.releasemanager.provider;

import java.net.InetSocketAddress;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSettingsTest {

    @AfterEach
    void reset() {
        NetworkSettings.configure(0, null);
    }

    @Test
    void acceptsHttpProxiesWithOrWithoutAScheme() {
        InetSocketAddress address = InetSocketAddress.createUnresolved(
            "127.0.0.1", 8118);

        assertEquals(address, NetworkSettings.parseProxy("http://127.0.0.1:8118"));
        assertEquals(address, NetworkSettings.parseProxy("127.0.0.1:8118"));
        assertEquals(address, NetworkSettings.parseProxy(" 127.0.0.1:8118 "));
        assertNull(NetworkSettings.parseProxy(null));
        assertNull(NetworkSettings.parseProxy("   "));
    }

    @Test
    void explainsThatSocksIsNotSupported() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> NetworkSettings.parseProxy("socks5://127.0.0.1:9050"));

        assertTrue(error.getMessage().contains("HTTPTunnelPort"),
            error.getMessage());
    }

    @Test
    void rejectsProxiesWithoutAPort() {
        assertThrows(IllegalArgumentException.class,
            () -> NetworkSettings.parseProxy("http://127.0.0.1"));
        assertThrows(IllegalArgumentException.class,
            () -> NetworkSettings.parseProxy("http://:8118"));
        assertThrows(IllegalArgumentException.class,
            () -> NetworkSettings.parseProxy("https://127.0.0.1:8118"));
    }

    @Test
    void keepsDownloadsGenerousWhenTheApiTimeoutIsShort() {
        NetworkSettings.configure(5, "http://127.0.0.1:8118");

        assertEquals(Duration.ofSeconds(5), NetworkSettings.apiTimeout());
        assertEquals(Duration.ofMinutes(5), NetworkSettings.downloadTimeout());
        assertEquals(Duration.ofMinutes(2), NetworkSettings.rngitMetadataTimeout());
        assertEquals(Duration.ofMinutes(30), NetworkSettings.rngitFetchTimeout());
        assertTrue(NetworkSettings.proxy().isPresent());
    }

    @Test
    void raisesTheFloorsWhenTheApiTimeoutIsLong() {
        NetworkSettings.configure(3600, null);

        assertEquals(Duration.ofHours(1), NetworkSettings.apiTimeout());
        assertEquals(Duration.ofHours(1), NetworkSettings.downloadTimeout());
        assertTrue(NetworkSettings.proxy().isEmpty());
    }
}
