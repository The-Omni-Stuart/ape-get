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

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.StateStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Path launcher = bin.resolve("github__owner__repo");
        assertTrue(Files.isSymbolicLink(launcher));
        assertEquals("payload", Files.readString(launcher));
        assertEquals(launcher, handler.launcherPath("github", "owner/repo"));
    }

    @Test
    void upgradeKeepsOldVersionAndRepointsCurrent() throws Exception {
        Path first = Files.writeString(tempDir.resolve("app-1.AppImage"), "one");
        Path second = Files.writeString(tempDir.resolve("app-2.AppImage"), "two");

        handler.install(new InstallRequest(first, "github", "owner/repo", "v1.0.0"));
        Path installed = handler.install(new InstallRequest(second, "github", "owner/repo", "v2.0.0"));

        assertEquals("two", Files.readString(installed));
        assertEquals("two", Files.readString(bin.resolve("github__owner__repo")));
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
        assertEquals("two", Files.readString(bin.resolve("github__owner__repo")));
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
        Path launcher = bin.resolve("github__owner__repo");

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
            new AppManifest.Asset("app.AppImage", "https://example.com/app", null),
            new AppManifest.Install(
                "appimage",
                restored.toString(),
                appDir().toString(),
                new AppManifest.Uninstall(
                    bin.resolve("github__owner__repo").toString(), List.of(), null)),
            new AppManifest.Verification("unknown", "none", null, null, null),
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));

        handler.activateRestored(manifest, restored);

        assertEquals(Paths.get("versions/v1.0.0"),
            Files.readSymbolicLink(appDir().resolve("current")));
        assertEquals("one", Files.readString(bin.resolve("github__owner__repo")));
    }


    @Test
    void installsPortableWithUserCommand() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("Tool-1.2.3.AppImage"), "payload");

        Path installed = handler.install(new InstallRequest(
            asset, "github", "owner/repo", "v1.2.3", PackageKind.APPIMAGE, "tool", false));

        Path launcher = bin.resolve("tool");
        assertEquals(installed, appDir().resolve("versions/v1.2.3/Tool-1.2.3.AppImage"));
        assertTrue(Files.isSymbolicLink(launcher));
        assertEquals("payload", Files.readString(launcher));
        assertEquals(launcher, handler.launcherPath("github", "owner/repo", "tool"));
    }

    @Test
    void installsApeWithMagic() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("tool"), "MZqFpD='payload");

        Path installed = handler.install(new InstallRequest(
            asset, "github", "owner/tool", "v1.2.3", PackageKind.APE, "tool", false));

        assertEquals("MZqFpD='payload", Files.readString(installed));
        assertEquals("MZqFpD='payload", Files.readString(bin.resolve("tool")));
    }

    @Test
    void noCommandLeavesNoLauncher() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("tool.AppImage"), "payload");

        handler.install(new InstallRequest(
            asset, "github", "owner/tool", "v1.2.3", PackageKind.APPIMAGE, null, false));

        assertFalse(Files.exists(bin.resolve("github__owner__tool")));
        assertFalse(Files.exists(bin.resolve("tool")));
    }



    @Test
    void refusesToOverwriteUnmanagedCommand() throws Exception {
        Files.createDirectories(bin);
        Path existing = Files.writeString(bin.resolve("tool"), "#!/bin/sh\n");
        Path asset = Files.writeString(tempDir.resolve("tool.AppImage"), "payload");

        assertNull(handler.install(new InstallRequest(
            asset, "github", "owner/tool", "v1.2.3", PackageKind.APPIMAGE, "tool", false)));
        assertEquals("#!/bin/sh\n", Files.readString(existing));
    }

    @Test
    void createsOptionalDesktopEntry() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("tool.AppImage"), "payload");
        handler.install(new InstallRequest(
            asset, "github", "owner/tool", "v1.2.3", PackageKind.APPIMAGE,
            "tool", false, true, null, true));

        Path entry = handler.desktopEntryPath("github", "owner/tool", "tool");
        assertTrue(Files.isRegularFile(entry));
        String content = Files.readString(entry);
        assertTrue(content.contains("Name=tool"), content);
        assertTrue(content.contains("Exec=\"" + bin.resolve("tool") + "\""), content);
        assertTrue(content.contains("Terminal=true"), content);
    }

    @Test
    void uninstallPortableRemovesLauncherAndRoot() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("tool.AppImage"), "payload");
        Path installed = handler.install(new InstallRequest(
            asset, "github", "owner/repo", "v1.2.3", PackageKind.APPIMAGE, "tool", false));
        AppManifest manifest = new AppManifest(
            AppManifest.KIND, AppManifest.SCHEMA_VERSION,
            "github", "owner/repo", "v1.2.3", 1L,
            new AppManifest.Asset("tool.AppImage", "url", null),
            new AppManifest.Install("appimage", installed.toString(), appDir().toString(),
                "tool", new AppManifest.Uninstall(bin.resolve("tool").toString(),
                    List.of(), null)),
            new AppManifest.Verification("unknown", "none", null, null, null),
            List.of(), Instant.now(), Instant.now());

        assertTrue(handler.uninstallPortable(manifest));
        assertFalse(Files.exists(bin.resolve("tool"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(appDir()),
            "application state is removed by UninstallService after the manifest");
    }

    @Test
    void sameRepositoryOnAnotherProviderGetsItsOwnLauncher() throws Exception {
        Path first = Files.writeString(tempDir.resolve("one.AppImage"), "one");
        Path second = Files.writeString(tempDir.resolve("two.AppImage"), "two");

        handler.install(new InstallRequest(first, "github", "owner/repo", "v1.0.0"));
        handler.install(new InstallRequest(second, "gitlab", "owner/repo", "v2.0.0"));

        assertEquals("one", Files.readString(bin.resolve("github__owner__repo")));
        assertEquals("two", Files.readString(bin.resolve("gitlab__owner__repo")));
    }

    @Test
    void refusesToReplaceAnUnmanagedLauncherDirectory() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("app.AppImage"), "payload");
        Files.createDirectories(bin.resolve("github__owner__repo"));

        assertNull(handler.install(new InstallRequest(asset, "github", "owner/repo", "v1.0.0")));
        assertTrue(Files.isDirectory(bin.resolve("github__owner__repo")));
    }
}
