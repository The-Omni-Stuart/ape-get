package cz.cuni.mff.releasemanager.state;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppPathsTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsConfigIndexAndApplicationPaths() {
        AppPaths paths = new AppPaths(tempDir);

        assertEquals(tempDir.toAbsolutePath().normalize(), paths.root());
        assertEquals(paths.root().resolve("grm_config.json"), paths.configFile());
        assertEquals(paths.root().resolve("grm_index.json"), paths.indexFile());
        assertEquals(paths.root().resolve("grm_lock"), paths.lockFile());
        assertEquals(
            paths.root().resolve("applications").resolve("github").resolve("owner__repo"),
            paths.appDir("github", "owner/repo"));
        assertEquals(
            paths.appDir("github", "owner/repo").resolve("grm_manifest.json"),
            paths.manifestFile("github", "owner/repo"));
    }

    @Test
    void normalizesRepositoryDirectoryNames() {
        assertEquals("keepassxreboot__keepassxc",
            AppPaths.repoDirectoryName("KeePassXReboot/KeePassXC"));
        assertEquals("owner__repo-name", AppPaths.repoDirectoryName("owner/repo name"));
    }

    @Test
    void rejectsInvalidRepositoryNames() {
        assertThrows(IllegalArgumentException.class,
            () -> AppPaths.repoDirectoryName("owner"));
        assertThrows(IllegalArgumentException.class,
            () -> AppPaths.repoDirectoryName("owner//repo"));
    }

    @Test
    void keepsUnderscoreLookalikeNamesApart() {
        assertNotEquals(
            AppPaths.repoDirectoryName("a/b__c"),
            AppPaths.repoDirectoryName("a__b/c"));
    }

    @Test
    void buildsNestedRepositoryDirectoryNames() {
        assertEquals("group__sub__project",
            AppPaths.repoDirectoryName("group/sub/project"));
    }

}
