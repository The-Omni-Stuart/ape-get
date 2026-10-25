package cz.cuni.mff.releasemanager.install;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.state.StateStore;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UninstallServiceTest {

    @Test
    void usesPortableRemovalForPortableManifests() throws Exception {
        StateStore stateStore = mock(StateStore.class);
        PlatformHandler handler = mock(PlatformHandler.class);
        AppManifest manifest = new AppManifest(
            AppManifest.KIND, AppManifest.SCHEMA_VERSION,
            "github", "owner/tool", "1.0.0", 1L,
            new AppManifest.Asset("tool.AppImage", "url", null),
            new AppManifest.Install("appimage", "/app/tool", "/app", "tool", null,
                new AppManifest.Uninstall("/bin/tool", List.of(), null)),
            new AppManifest.Verification("unknown", "none", null, null, null),
            List.of(), Instant.now(), Instant.now());
        when(handler.uninstallPortable(manifest)).thenReturn(true);
        when(stateStore.removeManifest(any())).thenReturn(true);

        assertTrue(new UninstallService(stateStore, handler).remove(manifest));

        verify(handler).uninstallPortable(manifest);
        verify(handler, never()).uninstall(any());
    }
}
