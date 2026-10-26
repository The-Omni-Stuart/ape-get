package cz.cuni.mff.releasemanager.install;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.platform.PackageKind;
import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PackagePlannerTest {

    @TempDir
    Path tempDir;

    private CmdParser cmdParser;

    @BeforeEach
    void setUp() {
        cmdParser = mock(CmdParser.class);
    }

    @Test
    void plansAppImageWithDerivedCommand() {
        PackagePlanner planner = new PackagePlanner(Platform.OS.LINUX, cmdParser);
        RepoRef ref = new RepoRef("github", "owner/tool");
        Asset asset = new Asset("url", "tool-x86_64.AppImage");

        PackagePlan plan = planner.plan(ref, asset, null, null);

        assertEquals(PackageKind.APPIMAGE, plan.kind());
        assertEquals("tool", plan.command());
    }

    @Test
    void plansPortableExeOnWindows() {
        when(cmdParser.getKind()).thenReturn("portable-exe");
        PackagePlanner planner = new PackagePlanner(Platform.OS.WINDOWS, cmdParser);
        RepoRef ref = new RepoRef("github", "owner/tool");
        Asset asset = new Asset("url", "tool-1.2.3-windows-x64.exe");

        PackagePlan plan = planner.plan(ref, asset, PackageKind.PORTABLE_EXE, null);

        assertEquals(PackageKind.PORTABLE_EXE, plan.kind());
        assertEquals("tool", plan.command());
    }

    @Test
    void honoursExplicitCommandAndNoCommand() {
        when(cmdParser.getCommandName()).thenReturn("ff");
        PackagePlanner planner = new PackagePlanner(Platform.OS.LINUX, cmdParser);
        RepoRef ref = new RepoRef("github", "owner/fastfetch");
        Asset asset = new Asset("url", "fastfetch.AppImage");
        assertEquals("ff", planner.plan(ref, asset, PackageKind.APPIMAGE, null).command());

        when(cmdParser.isNoCommand()).thenReturn(true);
        assertNull(planner.plan(ref, asset, PackageKind.APPIMAGE, null).command());
    }

    @Test
    void keepsPreviousKindAndCommandOnUpdate() {
        PackagePlanner planner = new PackagePlanner(Platform.OS.WINDOWS, cmdParser);
        RepoRef ref = new RepoRef("github", "owner/tool");
        AppManifest previous = new AppManifest(
            AppManifest.KIND, AppManifest.SCHEMA_VERSION,
            "github", "owner/tool", "1.0.0", 1L,
            new AppManifest.Asset("tool.exe", "old", null),
            new AppManifest.Install("portable-exe", "path", "root", "oldtool",
                new AppManifest.Uninstall("shim", List.of(), null)),
            new AppManifest.Verification("unknown", "none", null, null, null),
            List.of(), Instant.now(), Instant.now());

        PackagePlan plan = planner.plan(
            ref, new Asset("url", "tool-2.0.exe"), null, previous);

        assertEquals(PackageKind.PORTABLE_EXE, plan.kind());
        assertEquals("oldtool", plan.command());
    }

    @Test
    void detectsApeAfterDownload() throws Exception {
        PackagePlanner planner = new PackagePlanner(Platform.OS.LINUX, cmdParser);
        Path ape = tempDir.resolve("tool");
        Files.writeString(ape, "MZqFpD='rest", StandardCharsets.US_ASCII);
        PackagePlan plan = new PackagePlan(
            PackageKind.NSIS, new Asset("url", "tool.exe"), null);

        assertEquals(PackageKind.APE, planner.confirmAfterDownload(plan, ape));
    }


    @Test
    void explicitAssetMayBeExtensionlessApe() {
        PackagePlanner planner = new PackagePlanner(Platform.OS.LINUX, cmdParser);
        Asset unknown = new Asset("url", "tool");

        assertEquals(0, planner.candidates(List.of(unknown), PackageKind.APE).size());
        assertEquals(1, planner.candidates(
            List.of(unknown), PackageKind.APE, true).size());
    }

    @Test
    void rejectsNativeInstallersOnTheWrongPlatform() {
        RepoRef ref = new RepoRef("github", "owner/tool");
        PackagePlanner linux = new PackagePlanner(Platform.OS.LINUX, cmdParser);
        assertNull(linux.plan(ref, new Asset("url", "tool.msi"), PackageKind.MSI, null));

        PackagePlanner windows = new PackagePlanner(Platform.OS.WINDOWS, cmdParser);
        assertNull(windows.plan(ref, new Asset("url", "tool.dmg"), PackageKind.DMG, null));
    }

    @Test
    void rejectsInvalidKindOption() {
        when(cmdParser.getKind()).thenReturn("nonsense");
        PackagePlanner planner = new PackagePlanner(Platform.OS.LINUX, cmdParser);

        assertThrows(IllegalArgumentException.class, planner::requestedKind);
    }
}
