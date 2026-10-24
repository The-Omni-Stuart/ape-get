package cz.cuni.mff.releasemanager.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ChecksumFileTest {

    private static final String HELLO =
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    private static final String OTHER = "b".repeat(64);

    @Test
    void findsStandardEntry() {
        var lookup = ChecksumFile.findSha256(HELLO + "  file.txt\n", "file.txt");

        assertTrue(lookup.hasEntries());
        assertFalse(lookup.ambiguous());
        assertEquals(HELLO, lookup.sha256().orElseThrow());
    }

    @Test
    void findsBinaryMarkerEntry() {
        var lookup = ChecksumFile.findSha256(HELLO + " *file.txt\n", "file.txt");

        assertEquals(HELLO, lookup.sha256().orElseThrow());
    }

    @Test
    void findsBareHashForSingleAsset() {
        var lookup = ChecksumFile.findSha256(HELLO + "\n", "file.txt");

        assertTrue(lookup.hasEntries());
        assertEquals(HELLO, lookup.sha256().orElseThrow());
    }

    @Test
    void findsEntryInAggregateFile() {
        var content = OTHER + "  other.txt\n" + HELLO + "  file.txt\n";

        var lookup = ChecksumFile.findSha256(content, "file.txt");

        assertEquals(HELLO, lookup.sha256().orElseThrow());
    }

    @Test
    void findsBsdStyleEntry() {
        var lookup = ChecksumFile.findSha256("SHA256 (file.txt) = " + HELLO, "file.txt");

        assertEquals(HELLO, lookup.sha256().orElseThrow());
    }

    @Test
    void matchesPathPrefixedFilename() {
        var lookup = ChecksumFile.findSha256(HELLO + "  ./file.txt\n", "file.txt");

        assertEquals(HELLO, lookup.sha256().orElseThrow());
    }

    @Test
    void reportsEntriesButMissingTarget() {
        var lookup = ChecksumFile.findSha256(OTHER + "  other.txt\n", "file.txt");

        assertTrue(lookup.hasEntries());
        assertTrue(lookup.sha256().isEmpty());
    }

    @Test
    void reportsMalformedFile() {
        var lookup = ChecksumFile.findSha256("not a checksum\n", "file.txt");

        assertFalse(lookup.hasEntries());
        assertTrue(lookup.sha256().isEmpty());
    }

    @Test
    void reportsAmbiguousEntries() {
        var content = HELLO + "  file.txt\n" + OTHER + "  file.txt\n";

        var lookup = ChecksumFile.findSha256(content, "file.txt");

        assertTrue(lookup.hasEntries());
        assertTrue(lookup.ambiguous());
        assertTrue(lookup.sha256().isEmpty());
    }

    @Test
    void ignoresCommentsAndBlankLines() {
        var content = "# checksums\n\n" + HELLO + "  file.txt\n";

        var lookup = ChecksumFile.findSha256(content, "file.txt");

        assertEquals(HELLO, lookup.sha256().orElseThrow());
    }
}
