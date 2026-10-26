package cz.cuni.mff.releasemanager.state;

import java.util.Map;

import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.types.UpdateChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GrmConfigTest {

    @Test
    void defaultsToStableAndNoAutoUpdate() {
        GrmConfig config = GrmConfig.defaults();

        assertEquals(UpdateChannel.STABLE, config.channel());
        assertEquals(AutoUpdateMode.OFF, config.autoUpdateMode());
        assertEquals(24, config.updateIntervalHours());
    }

    @Test
    void normalisesMissingAndInvalidUpdateSettings() {
        GrmConfig config = new GrmConfig(
            GrmConfig.KIND, GrmConfig.SCHEMA_VERSION,
            false, 1, null, false, Map.of(), true,
            "beta", "apply", 6, 30, null);

        assertEquals(UpdateChannel.BETA, config.channel());
        assertEquals(AutoUpdateMode.APPLY, config.autoUpdateMode());
        assertEquals(6, config.updateIntervalHours());
    }

    @Test
    void providerSavePreservesUpdateSettings() {
        GrmConfig config = new GrmConfig(
            GrmConfig.KIND, GrmConfig.SCHEMA_VERSION,
            false, 1, null, false, Map.of(), true,
            "beta", "check", 12, 45, "http://127.0.0.1:8118");

        GrmConfig updated = config.withProviders(Map.of());
        assertEquals("beta", updated.updateChannel());
        assertEquals("check", updated.autoUpdate());
        assertEquals(12, updated.updateIntervalHours());
        assertEquals(45, updated.networkTimeoutSeconds());
        assertEquals("http://127.0.0.1:8118", updated.proxy());
        assertSame(GrmConfig.KIND, updated.kind());
    }

    @Test
    void normalisesNetworkSettings() {
        GrmConfig config = new GrmConfig(
            GrmConfig.KIND, GrmConfig.SCHEMA_VERSION,
            false, 1, null, false, Map.of(), true,
            "stable", "off", 24, 0, "  ");

        assertEquals(30, config.networkTimeoutSeconds());
        assertEquals(java.time.Duration.ofSeconds(30), config.networkTimeout());
        assertEquals(null, config.proxy());
    }
}
