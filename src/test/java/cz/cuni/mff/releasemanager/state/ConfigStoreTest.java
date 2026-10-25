package cz.cuni.mff.releasemanager.state;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.cuni.mff.releasemanager.provider.ProviderConfig;

class ConfigStoreTest {

    @TempDir
    Path tempDir;

    private ConfigStore store() {
        return new ConfigStore(new AppPaths(tempDir));
    }

    @Test
    void createsDefaultsOnFirstRun() throws Exception {
        ConfigStore store = store();

        ConfigStore.LoadResult result = store.load();

        assertTrue(result.created());
        assertNull(result.quarantined());
        assertEquals(GrmConfig.KIND, result.config().kind());
        assertEquals(GrmConfig.SCHEMA_VERSION, result.config().schemaVersion());
        assertEquals(1, result.config().backupCount());
        assertFalse(result.config().requireSignature());
        assertTrue(result.config().searchAllEnabled());
        assertTrue(Files.exists(new AppPaths(tempDir).configFile()));
        String json = Files.readString(new AppPaths(tempDir).configFile());
        assertFalse(json.contains("\"valid\""));
    }

    @Test
    void readsExistingConfigWithoutRecreatingIt() throws Exception {
        ConfigStore store = store();
        store.load();

        ConfigStore.LoadResult result = store.load();

        assertFalse(result.created());
        assertNull(result.quarantined());
        assertEquals(GrmConfig.KIND, result.config().kind());
    }

    @Test
    void quarantinesCorruptConfigAndCreatesDefaults() throws Exception {
        AppPaths paths = new AppPaths(tempDir);
        Files.createDirectories(paths.root());
        Files.writeString(paths.configFile(), "garbage");

        ConfigStore.LoadResult result = store().load();

        assertFalse(result.created());
        assertNotNull(result.quarantined());
        assertFalse(Files.exists(paths.configFile().getParent().resolve("garbage")));
        assertTrue(Files.exists(paths.configFile()));
        try (var files = Files.list(paths.root())) {
            assertTrue(files.anyMatch(path ->
                path.getFileName().toString().startsWith("grm_config.json.corrupt-")));
        }
    }

    @Test
    void quarantinesConfigWithWrongKind() throws Exception {
        AppPaths paths = new AppPaths(tempDir);
        Files.createDirectories(paths.root());
        Files.writeString(paths.configFile(), """
            { "kind": "other", "schema_version": 1 }
            """);

        ConfigStore.LoadResult result = store().load();

        assertNotNull(result.quarantined());
        assertEquals(GrmConfig.KIND, result.config().kind());
    }

    @Test
    void readsRequireSignature() throws Exception {
        AppPaths paths = new AppPaths(tempDir);
        Files.createDirectories(paths.root());
        Files.writeString(paths.configFile(), """
            { "kind": "grm.config", "schema_version": 1,
              "strict_verification": false, "backup_count": 1,
              "require_signature": true }
            """);

        ConfigStore.LoadResult result = store().load();

        assertTrue(result.config().requireSignature());
    }

    @Test
    void readsSearchAllProvidersFlag() throws Exception {
        AppPaths paths = new AppPaths(tempDir);
        Files.createDirectories(paths.root());
        Files.writeString(paths.configFile(), """
            { "kind": "grm.config", "schema_version": 1,
              "strict_verification": false, "backup_count": 1,
              "search_all_providers": false }
            """);

        assertFalse(store().load().config().searchAllEnabled());
    }

    @Test
    void readsProviderConfig() throws Exception {
        AppPaths paths = new AppPaths(tempDir);
        Files.createDirectories(paths.root());
        Files.writeString(paths.configFile(), """
            { "kind": "grm.config", "schema_version": 1,
              "strict_verification": false, "backup_count": 1,
              "providers": {
                "work": { "type": "gitea", "url": "https://git.example.com",
                          "token_env": "GRM_WORK_TOKEN" }
              } }
            """);

        ConfigStore.LoadResult result = store().load();

        ProviderConfig work = result.config().providers().get("work");
        assertEquals("gitea", work.type());
        assertEquals("GRM_WORK_TOKEN", work.tokenEnv());
        assertEquals("https://git.example.com/api/v1", work.resolvedApi());
    }

    @Test
    void savesProviderConfig() throws Exception {
        GrmConfig config = new GrmConfig(
            GrmConfig.KIND,
            GrmConfig.SCHEMA_VERSION,
            false,
            1,
            null,
            false,
            java.util.Map.of("work", new ProviderConfig(
                "gitea", "https://git.example.com", null, null)));

        store().save(config);

        ProviderConfig work = store().load().config().providers().get("work");
        assertEquals("gitea", work.type());
        assertEquals("https://git.example.com/api/v1", work.resolvedApi());
    }

    @Test
    void leavesNoTemporaryFileAfterWrite() throws Exception {
        AppPaths paths = new AppPaths(tempDir);
        store().load();

        try (var files = Files.list(paths.root())) {
            assertEquals(1, files.count());
        }
    }
}
