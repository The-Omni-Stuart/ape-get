package cz.cuni.mff.releasemanager.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class MacHandlerTest {

    @Test
    void findsTheBundleForAVersionedAssetName() throws Exception {
        Path app = Path.of("/Volumes/github-releases-manager.app");

        assertEquals(app, MacHandler.chooseApp(
            List.of(Path.of("/Volumes/Applications"), app),
            "github-releases-manager-macos-arm64.dmg"));
    }

    @Test
    void findsTheBundleWhenTheAssetNameDoesNotMatch() throws Exception {
        Path app = Path.of("/Volumes/Google Chrome.app");

        assertEquals(app, MacHandler.chooseApp(
            List.of(Path.of("/Volumes/Applications"), app), "google-chrome.dmg"));
    }

    @Test
    void prefersAnExactBundleName() throws Exception {
        Path app = Path.of("/Volumes/Tool.app");

        assertEquals(app, MacHandler.chooseApp(
            List.of(Path.of("/Volumes/Tool Helper.app"), app), "tool.dmg"));
    }

    @Test
    void reportsAMissingBundleInsteadOfCrashingOnShortNames() {
        assertThrows(IOException.class, () -> MacHandler.chooseApp(
            List.of(Path.of("/Volumes/a")), "anything.dmg"));
    }
}
