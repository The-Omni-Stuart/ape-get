package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.cuni.mff.releasemanager.types.Asset;

class RngitProviderTest {

    private static final String DESTINATION = "rns://7649a50d84610232d1416b41d2896aff";
    private static final ProviderConfig CONFIG = new ProviderConfig(
        "node", "rngit", DESTINATION, null, null);
    private static final String VIEW = """
        Requesting path...
        Path resolved      \r                       \rRelease : v1.2.0
        Status  : published
        Created : 2025-01-15 14:32:00
        Thanks  : 2

        Release Notes
        =============

        Fixes things

        Artifacts (2)
        =============
         - myapp-1.2.0.AppImage (1.5 MB)
         - myapp-1.2.0.zip (1.6 MB)

        """;

    @TempDir
    Path tempDir;

    @Test
    void latestReadsThePublishedTagAndArtifacts() throws Exception {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        RngitCommand command = (args, directory, timeout) -> {
            seen.set(List.copyOf(args));
            return new RngitCommand.Result(0, VIEW);
        };

        ReleaseAssets release = new RngitProvider(CONFIG, command)
            .latest("reticulum/rngit");

        assertEquals(List.of("release",
            DESTINATION + "/reticulum/rngit", "view", "latest"), seen.get());
        assertEquals("v1.2.0", release.version());
        assertEquals(List.of("myapp-1.2.0.AppImage", "myapp-1.2.0.zip"),
            release.all().stream().map(Asset::name).toList());
        assertTrue(release.all().get(0).url().startsWith("rngit://"));
    }

    @Test
    void releaseUsesTheRequestedTag() throws Exception {
        RngitProvider provider = new RngitProvider(CONFIG, (args, directory, timeout) -> {
            assertEquals("v1.0.0", args.get(3));
            return new RngitCommand.Result(0, VIEW.replace("v1.2.0", "v1.0.0"));
        });

        ReleaseAssets release = provider.release("reticulum/rngit", "v1.0.0");

        assertEquals("v1.0.0", release.version());
    }

    @Test
    void downloadFetchesTheSelectedArtifact() throws Exception {
        AtomicReference<List<String>> fetch = new AtomicReference<>();
        RngitCommand command = (args, directory, timeout) -> {
            if (args.contains("view")) {
                return new RngitCommand.Result(0, VIEW);
            }
            fetch.set(List.copyOf(args));
            Files.writeString(directory.resolve("myapp-1.2.0.AppImage"), "payload");
            return new RngitCommand.Result(0, "Fetching 1 artifact...");
        };
        RngitProvider provider = new RngitProvider(CONFIG, command);
        Asset asset = provider.latest("reticulum/rngit").all().get(0);

        Path downloaded = provider.download(asset);

        assertEquals("payload", Files.readString(downloaded));
        assertEquals(List.of("release", DESTINATION + "/reticulum/rngit",
            "fetch", "v1.2.0:myapp-1.2.0.AppImage"), fetch.get());
    }

    @Test
    void rejectsRepositoryPathsWithoutGroupAndName() {
        RngitProvider provider = new RngitProvider(
            CONFIG, (args, directory, timeout) -> new RngitCommand.Result(0, VIEW));

        IOException flat = assertThrows(IOException.class,
            () -> provider.latest("rngit"));
        assertTrue(flat.getMessage().contains("group/repo"), flat.getMessage());

        IOException nested = assertThrows(IOException.class,
            () -> provider.latest("group/team/rngit"));
        assertTrue(nested.getMessage().contains("group/repo"), nested.getMessage());
    }

    @Test
    void rejectsTagsThatWouldChangeTheRemoteRequest() {
        RngitProvider provider = new RngitProvider(
            CONFIG, (args, directory, timeout) -> new RngitCommand.Result(0, VIEW));

        assertThrows(IOException.class,
            () -> provider.release("group/repo", "v1/2"));
        assertThrows(IOException.class,
            () -> provider.release("group/repo", "--latest"));
    }

    @Test
    void downloadRejectsForeignAssetUrls() {
        RngitProvider provider = new RngitProvider(
            CONFIG, (args, directory, timeout) -> new RngitCommand.Result(0, ""));

        assertThrows(IOException.class, () -> provider.download(
            new Asset("https://example.com/myapp.AppImage", "myapp.AppImage")));
    }

    @Test
    void searchIsExplicitlyUnsupported() {
        RngitProvider provider = new RngitProvider(
            CONFIG, (args, directory, timeout) -> new RngitCommand.Result(0, ""));

        assertThrows(IOException.class, () -> provider.search("anything"));
    }
}
