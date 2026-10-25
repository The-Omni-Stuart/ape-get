package cz.cuni.mff.releasemanager.platform;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.cuni.mff.releasemanager.ReleaseManager;
import cz.cuni.mff.releasemanager.provider.ProviderConfig;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.AppPaths;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.StateStore;

/**
 * Exercises provider purge with the real platform handler on macOS and Windows.
 */
@EnabledOnOs({OS.MAC, OS.WINDOWS})
class ProviderPurgeNativeTest {

    @TempDir
    Path tempDir;

    private PlatformHandler handler;
    private StateStore store;
    private Object originalStateStore;
    private Object originalHandler;

    @AfterEach
    void tearDown() throws Exception {
        if (handler != null) {
            handler.setInstallRoot(null);
        }
        if (originalStateStore != null) {
            set(ReleaseManager.getInstance(), "stateStore", originalStateStore);
        }
        if (originalHandler != null) {
            set(ReleaseManager.getInstance(), "platformHandler", originalHandler);
        }
    }

    @Test
    @Timeout(120)
    void purgesProviderWithInstalledBundleOnMac() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Platform.detectPlatform().getOS() == Platform.OS.MAC);

        Path bundle = Files.createDirectories(
            tempDir.resolve("Applications/TestApp.app/Contents/MacOS"));
        Files.writeString(bundle.resolve("TestApp"), "#!/bin/sh\n");
        Path app = bundle.getParent().getParent();
        runPurge(app, "dmg");

        assertFalse(Files.exists(app), "application bundle should be removed");
        assertPurged();
    }

    @Test
    @Timeout(120)
    void purgesProviderWhenWindowsInstallIsAlreadyMissing() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Platform.detectPlatform().getOS() == Platform.OS.WINDOWS);

        Path missing = tempDir.resolve("ProgramFiles/work__owner__repo/uninst.exe");
        runPurge(missing, "exe");

        assertFalse(Files.exists(missing));
        assertPurged();
    }

    private void runPurge(Path installPath, String kind) throws Exception {
        AppPaths paths = new AppPaths(tempDir.resolve("state"));
        store = new StateStore(paths);
        Files.createDirectories(paths.root());
        ProviderConfig provider = new ProviderConfig(
            "work", "gitea", "https://git.example.com",
            "https://git.example.com/api/v1", null);
        store.saveConfig(new GrmConfig(
            GrmConfig.KIND,
            GrmConfig.SCHEMA_VERSION,
            false,
            1,
            null,
            false,
            Map.of("work", provider)));
        store.saveManifest(new AppManifest(
            AppManifest.KIND,
            AppManifest.SCHEMA_VERSION,
            "work",
            "owner/repo",
            "1.0.0",
            1L,
            new AppManifest.Asset(
                "app" + (kind.equals("dmg") ? ".dmg" : ".exe"),
                "https://example.invalid/app", null, null),
            new AppManifest.Install(
                kind,
                installPath.toString(),
                installPath.getParent().toString(),
                new AppManifest.Uninstall(installPath.toString(), List.of(), null)),
            new AppManifest.Verification("unknown", "none", null, null, null),
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z")));
        store.rebuildIndex();

        handler = Platform.detectPlatform().getOS() == Platform.OS.MAC
            ? MacHandler.getInstance()
            : WindowsHandler.getInstance();
        handler.setInstallRoot(tempDir.resolve(
            Platform.detectPlatform().getOS() == Platform.OS.MAC
                ? "Applications" : "ProgramFiles"));

        ReleaseManager manager = ReleaseManager.getInstance();
        originalStateStore = get(manager, "stateStore");
        originalHandler = get(manager, "platformHandler");
        set(manager, "stateStore", store);
        set(manager, "platformHandler", handler);

        assertEquals(0, manager.execute(new String[]{
            "providers", "remove", "work", "--purge", "--yes"}));
    }

    private void assertPurged() throws Exception {
        assertTrue(store.scanManifests().isEmpty());
        assertTrue(store.loadConfig().config().providers().isEmpty());
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = ReleaseManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = ReleaseManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
