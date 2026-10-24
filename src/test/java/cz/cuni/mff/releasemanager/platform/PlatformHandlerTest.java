package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.StateStore;
import cz.cuni.mff.releasemanager.utils.FileUtils;

class PlatformHandlerTest {

    class TestPlatformHandler extends PlatformHandler {
        TestPlatformHandler(StateStore stateStore) {
            super(stateStore);
        }

        @Override
        public Path install(Path asset, String repoFullName) {
            return null;
        }

        @Override
        public boolean uninstall(Path asset) {
            return true;
        }

        @Override
        public String[] getFormats() {
            return new String[] {"zip"};
        }
    }

    private Path tempDir;
    private PlatformHandler handler;
    private StateStore stateStore;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createDirectories(Path.of("releases_test"));
        stateStore = new StateStore(new AppPaths(tempDir));
        handler = new TestPlatformHandler(stateStore);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (!Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort test cleanup
                }
            });
        }
    }

    private static AppManifest manifest(String repo, String installPath) {
        return new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            "github",
            repo,
            "1.0.0",
            1L,
            new AppManifest.Asset("file.zip", "https://example.com/file", null, null),
            new AppManifest.Install(
                "zip",
                installPath,
                "/tmp/root",
                new AppManifest.Uninstall(installPath, List.of(), null)),
            new AppManifest.Verification("missing", "sha256", null, null, null),
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void testIsUnder() {
        Path root = tempDir.resolve("root");

        assertTrue(PlatformHandler.isUnder(root.resolve("app"), root));
        assertFalse(PlatformHandler.isUnder(root, root));
        assertFalse(PlatformHandler.isUnder(tempDir.resolve("other"), root));
        assertFalse(PlatformHandler.isUnder(root.resolve("..").resolve("other"), root));
    }

    @Test
    void testIsOwnedByAnotherRepo() throws IOException {
        Path target = tempDir.resolve("app");
        Files.createDirectories(target);
        stateStore.saveManifest(manifest(
            "owner/repo1", target.resolve("uninstall.exe").toString()));

        assertTrue(handler.isOwnedByAnotherRepo(target, "owner/repo2"));
        assertFalse(handler.isOwnedByAnotherRepo(target, "owner/repo1"));
    }

    @Test
    void testLinuxUninstallRefusesPathOutsideInstallRoot() throws IOException {
        Path file = Files.createTempFile("outside", ".AppImage");
        try {
            assertFalse(LinuxHandler.getInstance().uninstall(file));
            assertTrue(Files.exists(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void testMacUninstallRefusesPathOutsideApplications() throws IOException {
        Path dir = Files.createTempDirectory("outside");
        try {
            assertFalse(MacHandler.getInstance().uninstall(dir));
            assertTrue(Files.exists(dir));
        } finally {
            FileUtils.deleteRecursively(dir);
        }
    }

    @Test
    void testWindowsUninstallRefusesPathOutsideInstallRoots() throws IOException {
        Path file = Files.createTempFile("outside", ".exe");
        try {
            assertFalse(WindowsHandler.getInstance().uninstall(file));
            assertTrue(Files.exists(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
