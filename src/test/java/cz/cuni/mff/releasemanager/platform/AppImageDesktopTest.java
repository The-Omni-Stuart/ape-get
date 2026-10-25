package cz.cuni.mff.releasemanager.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppImageDesktopTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesOnlyTheDesktopEntryGroup() throws Exception {
        Path desktop = tempDir.resolve("app.desktop");
        Files.writeString(desktop, """
            [Desktop Entry]
            Name=Example
            Exec=example %U
            Terminal=false
            Categories=Utility;

            [Desktop Action New]
            Name=New Window
            """);

        Map<String, String> entries = AppImageDesktop.parse(desktop);

        assertEquals("Example", entries.get("Name"));
        assertEquals("example %U", entries.get("Exec"));
        assertEquals("Utility;", entries.get("Categories"));
        assertTrue(!entries.containsKey("New Window"));
    }

    @Test
    void prefersTheShortestMatchingIcon() throws Exception {
        Path apps = tempDir.resolve("usr/share/icons/hicolor/apps");
        Files.createDirectories(apps);
        Files.writeString(apps.resolve("example-monochrome.svg"), "mono");
        Files.writeString(apps.resolve("example.png"), "main");

        Path icon = AppImageDesktop.findIcon(tempDir, "example");

        assertEquals("example.png", icon.getFileName().toString());
    }
}
