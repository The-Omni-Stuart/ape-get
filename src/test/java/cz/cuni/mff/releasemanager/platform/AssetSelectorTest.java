package cz.cuni.mff.releasemanager.platform;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.types.Asset;

class AssetSelectorTest {

    private static Asset asset(String name) {
        return new Asset("https://example.invalid/" + name, name);
    }

    private static List<String> names(List<Asset> assets) {
        return assets.stream().map(Asset::name).toList();
    }

    @Test
    void prefersArm64AndUniversal() {
        List<Asset> assets = List.of(
            asset("app-x86_64.AppImage"),
            asset("app-arm64.AppImage"),
            asset("app-universal.AppImage"));

        List<Asset> selected = AssetSelector.preferArchitecture(
            assets, Platform.Architecture.ARM_64);

        assertEquals(List.of("app-arm64.AppImage", "app-universal.AppImage"),
            names(selected));
    }

    @Test
    void prefersX86_64AndUniversal() {
        List<Asset> assets = List.of(
            asset("app-arm64.AppImage"),
            asset("app-x86_64.AppImage"),
            asset("app-universal.AppImage"));

        List<Asset> selected = AssetSelector.preferArchitecture(
            assets, Platform.Architecture.X86_64);

        assertEquals(List.of("app-x86_64.AppImage", "app-universal.AppImage"),
            names(selected));
    }

    @Test
    void keepsUntaggedAssets() {
        List<Asset> assets = List.of(
            asset("app.AppImage"),
            asset("app-x86_64.AppImage"));

        List<Asset> selected = AssetSelector.preferArchitecture(
            assets, Platform.Architecture.ARM_64);

        assertEquals(List.of("app.AppImage"), names(selected));
    }

    @Test
    void fallsBackWhenEveryAssetIsForeign() {
        List<Asset> assets = List.of(asset("app-x86_64.AppImage"));

        List<Asset> selected = AssetSelector.preferArchitecture(
            assets, Platform.Architecture.ARM_64);

        assertEquals(List.of("app-x86_64.AppImage"), names(selected));
    }

    @Test
    void returnsAllWhenNoAssetIsTagged() {
        List<Asset> assets = List.of(
            asset("app.AppImage"), asset("app-2.AppImage"));

        List<Asset> selected = AssetSelector.preferArchitecture(
            assets, Platform.Architecture.ARM_64);

        assertEquals(2, selected.size());
    }

    @Test
    void doesNotTreatX86_64AsX86_32() {
        assertEquals(Platform.Architecture.X86_64,
            AssetSelector.detect("tool-x86_64.zip"));
    }

    @Test
    void detectsCommonTokens() {
        assertEquals(Platform.Architecture.ARM_64,
            AssetSelector.detect("tool-aarch64.tar.gz"));
        assertEquals(Platform.Architecture.X86_64,
            AssetSelector.detect("tool-amd64.deb"));
        assertEquals(Platform.Architecture.ARM_32,
            AssetSelector.detect("tool-armv7l.tar.gz"));
        assertEquals(Platform.Architecture.UNIVERSAL,
            AssetSelector.detect("tool-universal2.dmg"));
        assertEquals(null, AssetSelector.detect("tool.zip"));
    }
}
