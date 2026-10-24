package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.ReleaseInfo;
import cz.cuni.mff.releasemanager.types.ReleasesList;
import cz.cuni.mff.releasemanager.utils.FileUtils;

class PlatformHandlerTest {

    class TestPlatformHandler extends PlatformHandler {
        private final String temp = "temp";

        @Override
        public Path install(Path asset, String repoFullName) { return null; }

        @Override
        public boolean uninstall(Path asset) { return true; }

        @Override
        public String[] getFormats() { return new String[] { "zip" }; }

        @Override
        protected Path getReleasesListDirLocation() { return tempDir.resolve(temp); }
    }

    private Path tempDir;
    private PlatformHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createDirectories(Path.of("releases_test"));
        handler = new TestPlatformHandler();
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

    private ReleaseInfo createRelease(String repo) {
        Asset asset = new Asset("url", "name");
        return new ReleaseInfo(repo,  Instant.now(), "path", asset);
    }

    @Test
    void testAddReleaseToList() throws IOException {
        ReleaseInfo release = createRelease("repo1");
        handler.addReleaseToList(release);

        ReleasesList list = handler.loadReleasesList();
        assertNotNull(list);
        assertEquals(1, list.releases().size());
        assertEquals("repo1", list.releases().get(0).repo());
    }

    @Test
    void testAddReleaseToList_updateExisting() throws IOException {
        ReleaseInfo release1 = createRelease("repo1");
        handler.addReleaseToList(release1);

        ReleaseInfo release2 = createRelease("repo1");
        handler.addReleaseToList(release2);

        ReleasesList list = handler.loadReleasesList();
        assertNotNull(list);
        assertEquals(1, list.releases().size());
        assertEquals("repo1", list.releases().get(0).repo());
    }

    @Test
    void testRemoveReleaseFromList() throws IOException {
        ReleaseInfo release1 = createRelease("repo1");
        ReleaseInfo release2 = createRelease("repo2");
        handler.addReleaseToList(release1);
        handler.addReleaseToList(release2);

        handler.removeReleaseFromList(release1);

        ReleasesList list = handler.loadReleasesList();
        assertNotNull(list);
        assertEquals(1, list.releases().size());
        assertEquals("repo2", list.releases().get(0).repo());
    }

    @Test
    void testRemoveReleaseFromList_deletesFileIfEmpty() throws IOException {
        ReleaseInfo release = createRelease("repo1");
        handler.addReleaseToList(release);

        handler.removeReleaseFromList(release);

        assertFalse(Files.exists(handler.getReleasesListFileLocation()));
    }

    @Test
    void testLoadReleasesList_returnsNullIfFileDoesNotExist() throws IOException {
        assertNull(handler.loadReleasesList());
    }

    @Test
    void testLoadReleasesList_returnsNullForEmptyFile() throws IOException {
        Files.createDirectories(handler.getReleasesListDirLocation());
        Files.createFile(handler.getReleasesListFileLocation());

        assertNull(handler.loadReleasesList());
    }

    @Test
    void testAddReleaseToList_recoversFromEmptyFile() throws IOException {
        Files.createDirectories(handler.getReleasesListDirLocation());
        Files.createFile(handler.getReleasesListFileLocation());

        handler.addReleaseToList(createRelease("repo1"));

        ReleasesList list = handler.loadReleasesList();
        assertNotNull(list);
        assertEquals(1, list.releases().size());
        assertEquals("repo1", list.releases().get(0).repo());
    }

    @Test
    void testLoadReleasesList_quarantinesCorruptFile() throws IOException {
        Files.createDirectories(handler.getReleasesListDirLocation());
        Files.writeString(handler.getReleasesListFileLocation(), "garbage");

        assertNull(handler.loadReleasesList());
        assertFalse(Files.exists(handler.getReleasesListFileLocation()));
        try (var files = Files.list(handler.getReleasesListDirLocation())) {
            assertTrue(files.anyMatch(
                path -> path.getFileName().toString().startsWith("releases.json.corrupt-")));
        }
    }

    @Test
    void testLoadReleasesList_returnsNullForMissingReleasesField() throws IOException {
        Files.createDirectories(handler.getReleasesListDirLocation());
        Files.writeString(handler.getReleasesListFileLocation(), "{}");

        assertNull(handler.loadReleasesList());
    }

    @Test
    void testLoadReleasesList_quarantinesEntriesWithoutRepo() throws IOException {
        Files.createDirectories(handler.getReleasesListDirLocation());
        Files.writeString(handler.getReleasesListFileLocation(), "{\"releases\":[{}]}");

        assertNull(handler.loadReleasesList());
        assertFalse(Files.exists(handler.getReleasesListFileLocation()));
        try (var files = Files.list(handler.getReleasesListDirLocation())) {
            assertTrue(files.anyMatch(
                path -> path.getFileName().toString().startsWith("releases.json.corrupt-")));
        }
    }

    @Test
    void testLoadReleasesList_quarantinesEntriesMissingRequiredFields() throws IOException {
        Files.createDirectories(handler.getReleasesListDirLocation());
        Files.writeString(handler.getReleasesListFileLocation(),
            "{\"releases\":[{\"repo\":\"repo1\"}]}");

        assertNull(handler.loadReleasesList());
        assertFalse(Files.exists(handler.getReleasesListFileLocation()));
        try (var files = Files.list(handler.getReleasesListDirLocation())) {
            assertTrue(files.anyMatch(
                path -> path.getFileName().toString().startsWith("releases.json.corrupt-")));
        }
    }

    @Test
    void testAddReleaseToList_writesOnlyFinalFile() throws IOException {
        handler.addReleaseToList(createRelease("repo1"));

        try (var files = Files.list(handler.getReleasesListDirLocation())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void testLoadReleasesList_keepsValidEntriesWhenSomeAreInvalid() throws IOException {
        Files.createDirectories(handler.getReleasesListDirLocation());
        Files.writeString(handler.getReleasesListFileLocation(), """
            {"releases":[
              {"repo":"repo1","uninstall_path":"/tmp/repo1","asset":{"url":"url","name":"name"}},
              {}
            ]}
            """);

        ReleasesList list = handler.loadReleasesList();

        assertNotNull(list);
        assertEquals(1, list.releases().size());
        assertEquals("repo1", list.releases().get(0).repo());
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
        ReleaseInfo release = new ReleaseInfo(
            "repo1",
            Instant.now(),
            target.resolve("uninstall.exe").toString(),
            new Asset("url", "name"));
        handler.addReleaseToList(release);

        assertTrue(handler.isOwnedByAnotherRepo(target, "repo2"));
        assertFalse(handler.isOwnedByAnotherRepo(target, "repo1"));
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