package cz.cuni.mff.releasemanager.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class StagedAssetTest {

    @Test
    void discardRemovesTheFileAndItsEmptyDirectory() throws IOException {
        Path file = StagedAsset.create("app.bin");
        Files.writeString(file, "payload");

        StagedAsset.discard(file);

        assertFalse(Files.exists(file));
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void requireContentRejectsAnEmptyFile() throws IOException {
        Path file = StagedAsset.create("app.bin");
        try {
            assertThrows(IOException.class, () -> StagedAsset.requireContent(file, "app.bin"));

            Files.writeString(file, "payload");
            StagedAsset.requireContent(file, "app.bin");
            assertTrue(Files.size(file) > 0);
        } finally {
            StagedAsset.discard(file);
        }
    }
}
