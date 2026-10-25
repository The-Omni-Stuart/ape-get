package cz.cuni.mff.releasemanager.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

class VerificationResultTest {

    @Test
    void verifiedResultRecordsBothHashes() {
        VerificationResult result = VerificationResult.verified(
            "file.txt", "SHA256SUMS", "abc", "abc");

        assertEquals("abc", result.expected());
        assertEquals("abc", result.actual());
    }

    @Test
    void missingMessageDoesNotClaimContinuation() {
        assertFalse(VerificationResult.missing("file.txt").message().contains("continuing"));
    }

    @Test
    void unknownMessageDoesNotClaimContinuation() {
        assertFalse(VerificationResult.unknownAsset("file.txt", "SHA256SUMS")
            .message().contains("continuing"));
    }

    @Test
    void invalidMessageDoesNotClaimContinuation() {
        assertFalse(VerificationResult.invalid("file.txt", "checksums.txt")
            .message().contains("continuing"));
    }
}
