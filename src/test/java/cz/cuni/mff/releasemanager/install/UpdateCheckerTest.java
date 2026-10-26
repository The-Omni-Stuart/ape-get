package cz.cuni.mff.releasemanager.install;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.platform.Platform;
import cz.cuni.mff.releasemanager.platform.PlatformHandler;
import cz.cuni.mff.releasemanager.provider.ReleaseAssets;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.state.AppManifest;
import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.UpdateChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpdateCheckerTest {

    @Test
    void reportsChangedAssetAsOutdated() throws Exception {
        ReleaseProvider provider = mock(ReleaseProvider.class);
        when(provider.latest("owner/tool", UpdateChannel.STABLE))
            .thenReturn(new ReleaseAssets("2.0.0", 2L,
                List.of(new Asset("new", "tool.AppImage"))));
        UpdateChecker checker = new UpdateChecker(
            name -> provider, new ReleaseFetcher(), platform());

        UpdateChecker.Report report = checker.check(
            List.of(manifest("1.0.0", "old")), UpdateChannel.STABLE);

        assertFalse(report.failed());
        assertEquals(1, report.outdated().size());
        assertEquals("1.0.0", report.outdated().get(0).current());
        assertEquals("2.0.0", report.outdated().get(0).latest());
    }

    @Test
    void matchingAssetIsUpToDate() throws Exception {
        Asset asset = new Asset("same", "tool.AppImage");
        ReleaseProvider provider = mock(ReleaseProvider.class);
        when(provider.latest("owner/tool", UpdateChannel.STABLE))
            .thenReturn(new ReleaseAssets("1.0.0", 1L,
                List.of(asset)));
        UpdateChecker checker = new UpdateChecker(
            name -> provider, new ReleaseFetcher(), platform());

        UpdateChecker.Report report = checker.check(
            List.of(manifest("1.0.0", "same")), UpdateChannel.STABLE);

        assertFalse(report.failed());
        assertEquals(0, report.outdated().size());
    }

    private static PlatformHandler platform() {
        PlatformHandler platform = mock(PlatformHandler.class);
        when(platform.getFormats()).thenReturn(new String[]{".appimage"});
        when(platform.getArchitecture()).thenReturn(Platform.Architecture.X86_64);
        return platform;
    }

    private static AppManifest manifest(String version, String assetUrl) {
        return new AppManifest(
            AppManifest.KIND, AppManifest.SCHEMA_VERSION,
            "github", "owner/tool", version, 1L,
            new AppManifest.Asset("tool.AppImage", assetUrl, null),
            new AppManifest.Install("appimage", "/app/tool", "/app", "tool", null,
                new AppManifest.Uninstall("/bin/tool", List.of(), null)),
            new AppManifest.Verification("unknown", "none", null, null, null),
            List.of(), Instant.now(), Instant.now());
    }
}
