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

@EnabledOnOs(OS.LINUX)
class LinuxNativeInstallTest {

    @TempDir
    Path tempDir;

    private LinuxHandler handler;

    @BeforeEach
    void setUp() {
        handler = LinuxHandler.getInstance();
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

        Path installed = handler.install(asset, "owner/native");

        assertNotNull(installed);
        assertEquals(tempDir.resolve("bin").resolve("owner__native"), installed);
        assertTrue(Files.isRegularFile(installed));
        assertTrue(Files.isExecutable(installed));
        assertEquals("#!/bin/sh\necho native\n", Files.readString(installed));
        assertFalse(Files.exists(asset));

        assertTrue(handler.uninstall(installed));
        assertFalse(Files.exists(installed));
    }
}
