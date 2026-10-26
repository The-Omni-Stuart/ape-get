package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import cz.cuni.mff.releasemanager.state.ProviderConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderProbeTest {

    @Test
    @Timeout(5)
    void reportsPresenceNotFoundRateLimitsAndUnsupportedProviders() throws Exception {
        ReleaseProvider present = provider("github");
        when(present.latest("owner/repo")).thenReturn(
            new ReleaseAssets("1.0", 1L, List.of()));

        ReleaseProvider missing = provider("codeberg");
        when(missing.latest("owner/repo")).thenThrow(
            new ProviderException(ProviderException.Reason.NOT_FOUND, "not found"));

        ReleaseProvider limited = provider("gitlab");
        when(limited.latest("owner/repo")).thenThrow(
            new ProviderException(ProviderException.Reason.RATE_LIMITED, "slow down"));

        ReleaseProvider unsupported = mock(ReleaseProvider.class);
        when(unsupported.config()).thenReturn(config("rns"));
        when(unsupported.supportsRepositoryLookup()).thenReturn(false);

        Map<String, ProviderProbe.Result> results = new ProviderProbe().probeAll(
            List.of(present, missing, limited, unsupported),
            "owner/repo", Duration.ofSeconds(2)).stream()
            .collect(Collectors.toMap(ProviderProbe.Result::provider, Function.identity()));

        assertTrue(results.get("github").exists());
        assertNull(results.get("github").error());

        assertFalse(results.get("codeberg").exists());
        assertNull(results.get("codeberg").error());

        assertEquals(ProviderException.Reason.RATE_LIMITED,
            results.get("gitlab").reason());
        assertEquals("slow down", results.get("gitlab").error());

        assertEquals(ProviderException.Reason.UNSUPPORTED, results.get("rns").reason());
    }

    @Test
    @Timeout(5)
    void timesOutSlowProviders() throws Exception {
        ReleaseProvider slow = provider("slow");
        when(slow.latest("owner/repo")).thenAnswer(invocation -> {
            Thread.sleep(2000);
            return new ReleaseAssets(List.of());
        });

        ProviderProbe.Result result = new ProviderProbe().probeAll(
            List.of(slow), "owner/repo", Duration.ofMillis(200)).get(0);

        assertFalse(result.exists());
        assertEquals(ProviderException.Reason.TIMEOUT, result.reason());
        assertEquals("timed out", result.error());
    }

    private static ReleaseProvider provider(String name) throws IOException, InterruptedException {
        ReleaseProvider provider = mock(ReleaseProvider.class);
        when(provider.config()).thenReturn(config(name));
        when(provider.supportsRepositoryLookup()).thenReturn(true);
        return provider;
    }

    private static ProviderConfig config(String name) {
        return new ProviderConfig(
            name, "gitea", "https://example.invalid", null, null);
    }
}
