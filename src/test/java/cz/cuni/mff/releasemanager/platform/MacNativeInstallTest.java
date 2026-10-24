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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.MAC)
class MacNativeInstallTest {

    @TempDir
    Path tempDir;

    private MacHandler handler;

    @BeforeEach
    void setUp() {
        handler = MacHandler.getInstance();
        handler.setInstallRoot(tempDir.resolve("Applications"));
    }

    @AfterEach
    void tearDown() {
        handler.setInstallRoot(null);
    }

    @Test
    @Timeout(300)
    void installsAdHocSignedDmgAndUninstalls() throws Exception {
        Path payload = Files.createDirectories(tempDir.resolve("payload"));
        Path app = payload.resolve("TestApp.app");
        Path contents = Files.createDirectories(app.resolve("Contents"));
        Path macos = Files.createDirectories(contents.resolve("MacOS"));
        Files.writeString(contents.resolve("Info.plist"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
              "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>CFBundleIdentifier</key><string>cz.cuni.mff.releasemanager.testapp</string>
              <key>CFBundleName</key><string>TestApp</string>
              <key>CFBundleExecutable</key><string>TestApp</string>
              <key>CFBundlePackageType</key><string>APPL</string>
            </dict>
            </plist>
            """);
        Path executable = Files.writeString(macos.resolve("TestApp"),
            "#!/bin/sh\necho native\n");
        Files.setPosixFilePermissions(executable,
            PosixFilePermissions.fromString("rwxr-xr-x"));

        run(tempDir, "codesign", "--force", "--deep", "--sign", "-", app.toString());
        Path dmg = tempDir.resolve("TestApp.dmg");
        run(tempDir, "hdiutil", "create", "-volname", "TestApp",
            "-srcfolder", payload.toString(), "-ov", "-format", "UDZO", dmg.toString());

        Path installed = handler.install(dmg, "owner/native");

        assertNotNull(installed);
        assertEquals(tempDir.resolve("Applications").resolve("TestApp.app"), installed);
        assertTrue(Files.isRegularFile(installed.resolve("Contents/MacOS/TestApp")));
        run(tempDir, "codesign", "--verify", "--deep", "--strict", installed.toString());

        assertTrue(handler.uninstall(installed));
        assertFalse(Files.exists(installed));
    }

    private static void run(Path workingDirectory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .inheritIO()
            .start();
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, "command failed: " + String.join(" ", command));
    }
}
