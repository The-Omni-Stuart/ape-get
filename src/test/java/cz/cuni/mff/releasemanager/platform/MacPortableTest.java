package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.StateStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class MacPortableTest {

    @TempDir
    Path tempDir;

    private String oldHome;
    private Path home;
    private MacHandler handler;
    private AppPaths paths;

    @BeforeEach
    void setUp() {
        oldHome = System.getProperty("user.home");
        home = tempDir.resolve("home");
        System.setProperty("user.home", home.toString());
        paths = new AppPaths(tempDir.resolve("state"));
        handler = new MacHandler(new StateStore(paths));
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", oldHome);
    }

    @Test
    void installsApeWithCommandSymlink() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("tool"), "MZqFpD='payload");

        Path installed = handler.install(new InstallRequest(
            asset, "github", "owner/tool", "1.0.0",
            PackageKind.APE, "tool", false));

        Path launcher = home.resolve(".local/bin/tool");
        assertEquals("MZqFpD='payload", Files.readString(installed));
        assertTrue(Files.isSymbolicLink(launcher));
        assertEquals("MZqFpD='payload", Files.readString(launcher));
        assertEquals(launcher, handler.launcherPath("github", "owner/tool", "tool"));
    }

    @Test
    void uninstallPortableRemovesLauncher() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("tool"), "MZqFpD='payload");
        Path installed = handler.install(new InstallRequest(
            asset, "github", "owner/tool", "1.0.0",
            PackageKind.APE, "tool", false));
        var manifest = new cz.cuni.mff.releasemanager.state.AppManifest(
            cz.cuni.mff.releasemanager.state.AppManifest.KIND,
            cz.cuni.mff.releasemanager.state.AppManifest.SCHEMA_VERSION,
            "github", "owner/tool", "1.0.0", 1L,
            new cz.cuni.mff.releasemanager.state.AppManifest.Asset(
                "tool", "url", null),
            new cz.cuni.mff.releasemanager.state.AppManifest.Install(
                "ape", installed.toString(),
                paths.appDir("github", "owner/tool").toString(), "tool", null,
                new cz.cuni.mff.releasemanager.state.AppManifest.Uninstall(
                    home.resolve(".local/bin/tool").toString(),
                    java.util.List.of(), null)),
            new cz.cuni.mff.releasemanager.state.AppManifest.Verification(
                "unknown", "none", null, null, null),
            java.util.List.of(), java.time.Instant.now(), java.time.Instant.now());

        assertTrue(handler.uninstallPortable(manifest));
        assertFalse(Files.exists(home.resolve(".local/bin/tool")));
        assertTrue(Files.exists(installed));
    }
}
