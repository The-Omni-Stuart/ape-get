package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Files;
import java.nio.file.Path;
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

        String productCode = handler.productCode(installed);
        assertNotNull(productCode);
        assertTrue(productCode.startsWith("{") && productCode.endsWith("}"),
            productCode);
        assertEquals(PlatformHandler.InstallState.INSTALLED, handler.productInstallState(productCode));

        assertTrue(handler.uninstall(installed, productCode));
        assertFalse(Files.exists(app));
        assertEquals(PlatformHandler.InstallState.MISSING, handler.productInstallState(productCode));
    }

    @Test
    @Timeout(600)
    void rollsBackMsiByReinstallingTheCachedInstaller() throws Exception {
        Path fixture = Files.createDirectories(tempDir.resolve("rollback"));
        Path v1 = buildMsi(fixture, "v1", "1.0.0.0");
        Path cachedV1 = handler.install(v1, "owner/msi");
        assertNotNull(cachedV1);
        String codeV1 = handler.productCode(cachedV1);
        assertNotNull(codeV1);

        Path backupDir = Files.createDirectories(tempDir.resolve("backup"));
        Path backupInstaller = backupDir.resolve("v1.msi");
        Files.copy(cachedV1, backupInstaller);
        AppManifest restored = msiManifest(cachedV1, codeV1, "1.0.0.0");

        Path v2 = buildMsi(fixture, "v2", "2.0.0.0");
        Path cachedV2 = handler.install(v2, "owner/msi");
        assertNotNull(cachedV2);
        String codeV2 = handler.productCode(cachedV2);
        assertNotNull(codeV2);
        AppManifest current = msiManifest(cachedV2, codeV2, "2.0.0.0");

        Path app = installedApp();
        assertEquals("v2", Files.readString(app));
        assertEquals(PlatformHandler.InstallState.INSTALLED,
            handler.productInstallState(codeV2));

        assertTrue(handler.rollbackInstaller(current, restored, backupInstaller));

        assertTrue(Files.isRegularFile(app), "app.exe missing after rollback");
        assertEquals("v1", Files.readString(app));
        assertEquals(PlatformHandler.InstallState.INSTALLED,
            handler.productInstallState(codeV1));
        assertEquals(PlatformHandler.InstallState.MISSING,
            handler.productInstallState(codeV2));
    }

    private Path buildMsi(Path fixture, String name, String version) throws Exception {
        Files.writeString(fixture.resolve("app.exe"), name);
        String wxs = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
              <Product Id="*" Name="ApeGetMsiRollbackTest" Language="1033"
                       Version="%s" Manufacturer="ApeGet"
                       UpgradeCode="{D4C5E3F6-7081-4C9D-AEBF-2A3B4C5D6E7F}">
                <Package Id="*" InstallerVersion="500" Compressed="yes"
                         InstallScope="perMachine" />
                <MajorUpgrade DowngradeErrorMessage="A newer version is already installed." />
                <MediaTemplate EmbedCab="yes" />
                <Directory Id="TARGETDIR" Name="SourceDir">
                  <Directory Id="ProgramFiles64Folder">
                    <Directory Id="INSTALLDIR" Name="ApeGetMsiRollbackTest">
                      <Component Id="Main"
                                 Guid="{E5D6F407-8192-4DAE-BFC0-3B4C5D6E7F80}">
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
            """.formatted(version);
        Path wxsFile = fixture.resolve(name + ".wxs");
        Files.writeString(wxsFile, wxs);
        run(fixture, "candle", name + ".wxs");
        run(fixture, "light", "-sval", name + ".wixobj", "-out", name + ".msi");
        return fixture.resolve(name + ".msi");
    }

    private Path installedApp() {
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        Path app64 = Path.of(programFiles, "ApeGetMsiRollbackTest", "app.exe");
        Path app32 = programFilesX86 == null
            ? app64
            : Path.of(programFilesX86, "ApeGetMsiRollbackTest", "app.exe");
        return Files.isRegularFile(app64) ? app64 : app32;
    }

    private static AppManifest msiManifest(
        Path cachedInstaller, String productCode, String version) {
        return new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            "github",
            "owner/msi",
            version,
            1L,
            new AppManifest.Asset(
                cachedInstaller.getFileName().toString(), "https://example.invalid",
                null, null),
            new AppManifest.Install(
                "msi",
                cachedInstaller.toString(),
                cachedInstaller.getParent().toString(),
                new AppManifest.Uninstall(
                    cachedInstaller.toString(), List.of(), productCode)),
            new AppManifest.Verification("unknown", "none", null, null, null),
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
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
