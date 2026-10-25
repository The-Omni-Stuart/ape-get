package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.StateStore;

@EnabledOnOs(OS.LINUX)
class LinuxNativeInstallTest {

    @TempDir
    Path tempDir;

    private LinuxHandler handler;
    private AppPaths paths;

    @BeforeEach
    void setUp() {
        paths = new AppPaths(tempDir.resolve("state"));
        handler = new LinuxHandler(new StateStore(paths));
        handler.setInstallRoot(tempDir.resolve("bin"));
    }

    @AfterEach
    void tearDown() {
        handler.setInstallRoot(null);
    }

    @Test
    void installsAndUninstallsAppImage() throws Exception {
        Path asset = Files.writeString(tempDir.resolve("native.AppImage"),
            "#!/bin/sh\necho native\n");
        Files.setPosixFilePermissions(asset,
            PosixFilePermissions.fromString("rwxr-xr-x"));

        Path installed = handler.install(
            new InstallRequest(asset, "github", "owner/native", "v1.0.0"));

        assertNotNull(installed);
        assertEquals(paths.appDir("github", "owner/native")
            .resolve("versions/v1.0.0/native.AppImage"), installed);
        assertTrue(Files.isRegularFile(installed));
        assertTrue(Files.isExecutable(installed));
        assertEquals("#!/bin/sh\necho native\n", Files.readString(installed));
        assertFalse(Files.exists(asset));

        Path launcher = handler.launcherPath("owner/native");
        assertEquals(tempDir.resolve("bin").resolve("owner__native"), launcher);
        assertTrue(Files.isSymbolicLink(launcher));
        assertEquals("#!/bin/sh\necho native\n", Files.readString(launcher));

        assertTrue(handler.uninstall(launcher));
        assertFalse(Files.exists(launcher));
        assertTrue(Files.isRegularFile(installed));
    }
}
