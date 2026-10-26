package cz.cuni.mff.releasemanager.provider;

import java.util.Map;

import cz.cuni.mff.releasemanager.state.ProviderConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryTest {

    @Test
    void exposesBuiltIns() {
        ProviderRegistry registry = new ProviderRegistry();

        assertTrue(registry.get("github").isPresent());
        assertTrue(registry.get("codeberg").isPresent());
        assertTrue(registry.get("gitlab").isPresent());
        assertEquals(3, registry.all().size());
    }

    @Test
    void addsCustomGiteaInstance() {
        ProviderConfig config = new ProviderConfig(
            "gitea", "https://git.example.com", null, "GRM_WORK_TOKEN");
        ProviderRegistry registry = new ProviderRegistry(Map.of("work", config));

        ProviderConfig work = registry.get("work").orElseThrow();

        assertEquals("work", work.name());
        assertEquals("gitea", work.type());
        assertEquals("https://git.example.com/api/v1", work.resolvedApi());
        assertEquals("GRM_WORK_TOKEN", work.tokenEnv());
    }

    @Test
    void derivesGitHubEnterpriseApi() {
        ProviderConfig config = new ProviderConfig(
            "github", "https://ghe.example.com", null, null);

        assertEquals("https://ghe.example.com/api/v3", config.resolvedApi());
    }

    @Test
    void ignoresInvalidAndBuiltInOverrides() {
        ProviderConfig invalid = new ProviderConfig(
            "gitea", "http://insecure.example.com", null, null);
        ProviderConfig override = new ProviderConfig(
            "github", "https://evil.example.com", null, null);

        ProviderRegistry registry = new ProviderRegistry(Map.of(
            "bad", invalid,
            "github", override));

        assertTrue(registry.get("bad").isEmpty());
        assertEquals("https://github.com",
            registry.get("github").orElseThrow().url());
        assertEquals(3, registry.all().size());
    }
}
