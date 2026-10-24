package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Files;
import java.nio.file.Path;

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

@EnabledOnOs(OS.WINDOWS)
class WindowsNativeInstallTest {

    @TempDir
    Path tempDir;

    private WindowsHandler handler;

    @BeforeEach
    void setUp() {
        handler = WindowsHandler.getInstance();
        handler.setInstallRoot(tempDir.resolve("ProgramFiles"));
    }

    @AfterEach
    void tearDown() {
        handler.setInstallRoot(null);
    }

    @Test
    @Timeout(300)
    void installsAndUninstallsNsis() throws Exception {
        Path fixture = Files.createDirectories(tempDir.resolve("nsis"));
        Files.writeString(fixture.resolve("app.exe"), "not really an executable");
        Path installer = fixture.resolve("test-installer.exe");
        Files.writeString(fixture.resolve("install.nsi"), """
            Unicode true
            OutFile "%s"
            InstallDir "$PROGRAMFILES64\\ApeGetNsisTest"
            RequestExecutionLevel admin
            SilentInstall silent
            Section
              SetOutPath "$INSTDIR"
              File "app.exe"
              WriteUninstaller "$INSTDIR\\uninst.exe"
            SectionEnd
            Section "Uninstall"
              Delete "$INSTDIR\\app.exe"
              Delete "$INSTDIR\\uninst.exe"
              RMDir "$INSTDIR"
            SectionEnd
            """.formatted(installer));

        run(fixture, "makensis", "install.nsi");

        Path installed = handler.install(installer, "owner/native");

        assertNotNull(installed);
        Path target = tempDir.resolve("ProgramFiles").resolve("owner__native");
        assertTrue(Files.isRegularFile(target.resolve("app.exe")));
        assertTrue(Files.isRegularFile(target.resolve("uninst.exe")));

        assertTrue(handler.uninstall(installed));
        assertFalse(Files.exists(target.resolve("uninst.exe")));
    }

    @Test
    @Timeout(300)
    void installsAndUninstallsMsi() throws Exception {
        Path fixture = Files.createDirectories(tempDir.resolve("msi"));
        Files.writeString(fixture.resolve("app.exe"), "not really an executable");
        Files.writeString(fixture.resolve("test.wxs"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
              <Product Id="*" Name="ApeGetMsiTest" Language="1033" Version="1.0.0.0"
                       Manufacturer="ApeGet"
                       UpgradeCode="{B2A3C1D4-5E6F-4A7B-8C9D-0E1F2A3B4C5D}">
                <Package InstallerVersion="500" Compressed="yes" InstallScope="perMachine" />
                <MajorUpgrade DowngradeErrorMessage="A newer version is already installed." />
                <MediaTemplate EmbedCab="yes" />
                <Directory Id="TARGETDIR" Name="SourceDir">
                  <Directory Id="ProgramFiles64Folder">
                    <Directory Id="INSTALLDIR" Name="ApeGetMsiTest">
                      <Component Id="Main"
                                 Guid="{C3B4D2E5-6F70-4B8C-9DAE-1F2B3C4D5E6F}">
                        <File Id="AppExe" Source="app.exe" KeyPath="yes" />
                      </Component>
                    </Directory>
                  </Directory>
                </Directory>
                <Feature Id="Main" Level="1">
                  <ComponentRef Id="Main" />
                </Feature>
              </Product>
            </Wix>
            """);

        run(fixture, "candle", "test.wxs");
        run(fixture, "light", "-sval", "test.wixobj", "-out", "test.msi");

        Path installed = handler.install(fixture.resolve("test.msi"), "owner/msi");

        assertNotNull(installed);
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        Path app64 = Path.of(programFiles, "ApeGetMsiTest", "app.exe");
        Path app32 = programFilesX86 == null
            ? app64
            : Path.of(programFilesX86, "ApeGetMsiTest", "app.exe");
        Path app = Files.isRegularFile(app64) ? app64 : app32;
        assertTrue(Files.isRegularFile(app),
            "app.exe not found under " + app64 + " or " + app32);

        assertTrue(handler.uninstall(installed));
        assertFalse(Files.exists(app));
    }

    private void run(Path workingDirectory, String... command) throws Exception {
        Path log = tempDir.resolve("tool.log");
        Process process = new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = Files.exists(log) ? Files.readString(log) : "";
            throw new AssertionError(
                "command failed (" + exitCode + "): " + String.join(" ", command)
                    + "\n" + output);
        }
    }
}
