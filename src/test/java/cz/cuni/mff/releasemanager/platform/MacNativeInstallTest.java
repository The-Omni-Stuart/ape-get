package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;

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

import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.BackupManager;

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

        Path installed = handler.install(new InstallRequest(dmg, "github", "owner/native", null));

        assertNotNull(installed);
        assertEquals(tempDir.resolve("Applications").resolve("TestApp.app"), installed);
        assertTrue(Files.isRegularFile(installed.resolve("Contents/MacOS/TestApp")));
        run(tempDir, "codesign", "--verify", "--deep", "--strict", installed.toString());

        assertTrue(handler.uninstall(installed));
        assertFalse(Files.exists(installed));
    }

    @Test
    @Timeout(600)
    void backsUpAndRestoresTheApplicationBundle() throws Exception {
        Path v1 = dmgWithMarker("v1", "v1");
        Path installed = handler.install(new InstallRequest(v1, "github", "owner/native", null));
        assertNotNull(installed);
        assertTrue(Files.readString(
            installed.resolve("Contents/MacOS/TestApp")).contains("echo v1"));

        AppPaths paths = new AppPaths(tempDir.resolve("state"));
        BackupManager backups = new BackupManager(paths);
        List<AppManifest.Backup> created = backups.createAndPrune(
            manifest(installed), 1);
        assertEquals(1, created.size());
        assertTrue(Files.isDirectory(Path.of(created.get(0).artifact())));

        Path v2 = dmgWithMarker("v2", "v2");
        Path updated = handler.install(new InstallRequest(v2, "github", "owner/native", null));
        assertNotNull(updated);
        assertTrue(Files.readString(
            updated.resolve("Contents/MacOS/TestApp")).contains("echo v2"));

        backups.restore(created.get(0), updated);

        assertTrue(Files.readString(
            updated.resolve("Contents/MacOS/TestApp")).contains("echo v1"));
        run(tempDir, "codesign", "--verify", "--deep", "--strict",
            updated.toString());
    }

    private Path dmgWithMarker(String directory, String marker) throws Exception {
        Path payload = Files.createDirectories(tempDir.resolve(directory));
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
            "#!/bin/sh\necho " + marker + "\n");
        Files.setPosixFilePermissions(executable,
            PosixFilePermissions.fromString("rwxr-xr-x"));
        run(tempDir, "codesign", "--force", "--deep", "--sign", "-", app.toString());

        Path dmg = tempDir.resolve("TestApp-" + directory + ".dmg");
        run(tempDir, "hdiutil", "create", "-volname", "TestApp",
            "-srcfolder", payload.toString(), "-ov", "-format", "UDZO", dmg.toString());
        return dmg;
    }

    private static AppManifest manifest(Path installed) {
        return new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            "github",
            "owner/native",
            "1.0.0",
            1L,
            new AppManifest.Asset(
                "TestApp.dmg",
                "https://example.invalid/TestApp.dmg",
                null),
            new AppManifest.Install(
                "dmg",
                installed.toString(),
                installed.getParent().toString(),
                new AppManifest.Uninstall(installed.toString(), List.of(), null)),
            new AppManifest.Verification("unknown", "none", null, null, null),
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
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
