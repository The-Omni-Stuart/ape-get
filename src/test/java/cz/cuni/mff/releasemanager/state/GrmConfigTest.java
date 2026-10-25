package cz.cuni.mff.releasemanager.state;

import java.util.Map;

import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.provider.UpdateChannel;

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
            "beta", "apply", 6);

        assertEquals(UpdateChannel.BETA, config.channel());
        assertEquals(AutoUpdateMode.APPLY, config.autoUpdateMode());
        assertEquals(6, config.updateIntervalHours());
    }

    @Test
    void providerSavePreservesUpdateSettings() {
        GrmConfig config = new GrmConfig(
            GrmConfig.KIND, GrmConfig.SCHEMA_VERSION,
            false, 1, null, false, Map.of(), true,
            "beta", "check", 12);

        GrmConfig updated = config.withProviders(Map.of());
        assertEquals("beta", updated.updateChannel());
        assertEquals("check", updated.autoUpdate());
        assertEquals(12, updated.updateIntervalHours());
        assertSame(GrmConfig.KIND, updated.kind());
    }
}
