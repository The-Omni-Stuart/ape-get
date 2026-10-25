package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.StateStore;

@EnabledOnOs(OS.LINUX)
class LinuxHandlerTest {

    @TempDir
    Path tempDir;

    private LinuxHandler handler;
    private Path bin;
    private AppPaths paths;

    @BeforeEach
    void setUp() {
        bin = tempDir.resolve("bin");
        paths = new AppPaths(tempDir.resolve("state"));
        handler = new LinuxHandler(new StateStore(paths));
        handler.setInstallRoot(bin);
    }

    private Path appDir() {
        return paths.appDir("github", "owner/repo");
    }

    @Test
    void installsIntoVersionedDirectoryAndLinksCurrent() throws Exception {
        Path asset = Files.writeString(
            tempDir.resolve("Example-1.2.3.AppImage"), "payload");

        Path installed = handler.install(new InstallRequest(asset, "github", "owner/repo", "v1.2.3"));

        Path versionFile = appDir().resolve("versions/v1.2.3/Example-1.2.3.AppImage");
        assertEquals(versionFile, installed);
        assertTrue(Files.isRegularFile(versionFile));
        assertEquals(Paths.get("versions/v1.2.3"),
            Files.readSymbolicLink(appDir().resolve("current")));
        Path launcher = bin.resolve("owner__repo");
        assertTrue(Files.isSymbolicLink(launcher));
        assertEquals("payload", Files.readString(launcher));
        assertEquals(launcher, handler.launcherPath("owner/repo"));
    }

    @Test
    void upgradeKeepsOldVersionAndRepointsCurrent() throws Exception {
        Path first = Files.writeString(tempDir.resolve("app-1.AppImage"), "one");
        Path second = Files.writeString(tempDir.resolve("app-2.AppImage"), "two");

        handler.install(new InstallRequest(first, "github", "owner/repo", "v1.0.0"));
        Path installed = handler.install(new InstallRequest(second, "github", "owner/repo", "v2.0.0"));

        assertEquals("two", Files.readString(installed));
        assertEquals("two", Files.readString(bin.resolve("owner__repo")));
        assertTrue(Files.isRegularFile(
            appDir().resolve("versions/v1.0.0/app-1.AppImage")));
        assertEquals(Paths.get("versions/v2.0.0"),
            Files.readSymbolicLink(appDir().resolve("current")));
    }

    @Test
    void reinstallingSameVersionReplacesTheFile() throws Exception {
        Path first = Files.writeString(tempDir.resolve("app.AppImage"), "one");

        handler.install(new InstallRequest(first, "github", "owner/repo", "v1.0.0"));
        Path second = Files.writeString(tempDir.resolve("app.AppImage"), "two");
        Path installed = handler.install(
            new InstallRequest(second, "github", "owner/repo", "v1.0.0"));

        assertEquals("two", Files.readString(installed));
        assertEquals("two", Files.readString(bin.resolve("owner__repo")));
    }

    @Test
    void sanitizesHostileVersionNames() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("app.AppImage"), "payload");

        Path installed = handler.install(new InstallRequest(asset, "github", "owner/repo", "../../escape"));

        Path versions = appDir().resolve("versions").toAbsolutePath().normalize();
        assertTrue(installed.toAbsolutePath().normalize().startsWith(versions),
            installed.toString());
        assertFalse(Files.exists(appDir().getParent().getParent().resolve("escape")));
    }

    @Test
    void usesUnknownDirectoryForUntaggedVersions() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("app.AppImage"), "payload");

        Path installed = handler.install(new InstallRequest(asset, "github", "owner/repo", null));

        assertEquals(appDir().resolve("versions/unknown/app.AppImage"), installed);
        assertEquals(Paths.get("versions/unknown"),
            Files.readSymbolicLink(appDir().resolve("current")));
    }

    @Test
    void uninstallRemovesTheLauncherOnly() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("app.AppImage"), "payload");
        Path installed = handler.install(new InstallRequest(asset, "github", "owner/repo", "v1.0.0"));
        Path launcher = bin.resolve("owner__repo");

        assertTrue(handler.uninstall(launcher));

        assertFalse(Files.exists(launcher, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(installed));
    }

    @Test
    void activateRestoredRepointsCurrentAndLauncher() throws Exception {
        Path first = Files.writeString(tempDir.resolve("app.AppImage"), "one");
        handler.install(new InstallRequest(first, "github", "owner/repo", "v1.0.0"));
        Path second = Files.writeString(tempDir.resolve("app.AppImage"), "two");
        handler.install(new InstallRequest(second, "github", "owner/repo", "v2.0.0"));

        Path restored = appDir().resolve("versions/v1.0.0/app.AppImage");
        AppManifest manifest = new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            "github",
            "owner/repo",
            "v1.0.0",
            1L,
            new AppManifest.Asset("app.AppImage", "https://example.com/app", null, null),
            new AppManifest.Install(
                "appimage",
                restored.toString(),
                appDir().toString(),
                new AppManifest.Uninstall(
                    bin.resolve("owner__repo").toString(), List.of(), null)),
            new AppManifest.Verification("unknown", "none", null, null, null),
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));

        handler.activateRestored(manifest, restored);

        assertEquals(Paths.get("versions/v1.0.0"),
            Files.readSymbolicLink(appDir().resolve("current")));
        assertEquals("one", Files.readString(bin.resolve("owner__repo")));
    }

    @Test
    void refusesToReplaceAnUnmanagedLauncherDirectory() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("app.AppImage"), "payload");
        Files.createDirectories(bin.resolve("owner__repo"));

        assertNull(handler.install(new InstallRequest(asset, "github", "owner/repo", "v1.0.0")));
        assertTrue(Files.isDirectory(bin.resolve("owner__repo")));
    }
}
