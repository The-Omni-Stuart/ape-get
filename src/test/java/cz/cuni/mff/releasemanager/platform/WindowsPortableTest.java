package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.StateStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsPortableTest {

    @TempDir
    Path tempDir;

    private String oldHome;
    private Path home;
    private WindowsHandler handler;
    private AppPaths paths;

    @BeforeEach
    void setUp() {
        oldHome = System.getProperty("user.home");
        home = tempDir.resolve("home");
        System.setProperty("user.home", home.toString());
        paths = new AppPaths(tempDir.resolve("state"));
        handler = new WindowsHandler(new StateStore(paths));
    }

    @AfterEach
    void tearDown() throws Exception {
        System.setProperty("user.home", oldHome);
        Files.deleteIfExists(portableBin().resolve("tool.cmd"));
    }

    private Path portableBin() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData == null
            ? home.resolve(".local/bin")
            : Path.of(localAppData, "github-release-manager", "bin");
    }

    @Test
    void installsPortableExeWithShim() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("tool.exe"), "payload");

        Path installed = handler.install(new InstallRequest(
            asset, "github", "owner/tool", "1.0.0",
            PackageKind.PORTABLE_EXE, "tool", false));

        Path target = paths.appDir("github", "owner/tool")
            .resolve("versions/1.0.0/tool.exe");
        Path shim = portableBin().resolve("tool.cmd");
        assertEquals(target, installed);
        assertEquals("payload", Files.readString(target));
        assertTrue(Files.isRegularFile(shim));
        assertTrue(Files.readString(shim).contains(target.toString()));
        assertEquals(shim, handler.launcherPath("github", "owner/tool", "tool"));
    }

    @Test
    void uninstallPortableRemovesOnlyShim() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("tool.exe"), "payload");
        Path installed = handler.install(new InstallRequest(
            asset, "github", "owner/tool", "1.0.0",
            PackageKind.PORTABLE_EXE, "tool", false));
        var manifest = new cz.cuni.mff.releasemanager.state.AppManifest(
            cz.cuni.mff.releasemanager.state.AppManifest.KIND,
            cz.cuni.mff.releasemanager.state.AppManifest.SCHEMA_VERSION,
            "github", "owner/tool", "1.0.0", 1L,
            new cz.cuni.mff.releasemanager.state.AppManifest.Asset(
                "tool.exe", "url", null),
            new cz.cuni.mff.releasemanager.state.AppManifest.Install(
                "portable-exe", installed.toString(),
                paths.appDir("github", "owner/tool").toString(), "tool", null,
                new cz.cuni.mff.releasemanager.state.AppManifest.Uninstall(
                    portableBin().resolve("tool.cmd").toString(),
                    java.util.List.of(), null)),
            new cz.cuni.mff.releasemanager.state.AppManifest.Verification(
                "unknown", "none", null, null, null),
            java.util.List.of(), java.time.Instant.now(), java.time.Instant.now());

        assertTrue(handler.uninstallPortable(manifest));
        assertFalse(Files.exists(portableBin().resolve("tool.cmd")));
        assertTrue(Files.exists(installed));
    }
}
