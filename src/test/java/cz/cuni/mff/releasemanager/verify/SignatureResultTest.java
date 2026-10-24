package cz.cuni.mff.releasemanager.verify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SignatureResultTest {

    @Test
    void verifiedAlwaysAllowsInstall() {
        assertTrue(SignatureResult.verified("app", "gpg", "Signer", "FPR").allowsInstall(true));
        assertTrue(SignatureResult.verified("app", "gpg", "Signer", "FPR").allowsInstall(false));
    }

    @Test
    void missingOnlyBlocksWhenRequired() {
        assertTrue(SignatureResult.missing("app").allowsInstall(false));
        assertFalse(SignatureResult.missing("app").allowsInstall(true));
    }

    @Test
    void unavailableOnlyBlocksWhenRequired() {
        assertTrue(SignatureResult.unavailable("app", "gpg missing").allowsInstall(false));
        assertFalse(SignatureResult.unavailable("app", "gpg missing").allowsInstall(true));
    }

    @Test
    void invalidAndErrorAlwaysBlock() {
        assertFalse(SignatureResult.invalid("app", "gpg", "bad").allowsInstall(false));
        assertFalse(SignatureResult.error("app", "boom").allowsInstall(false));
    }

    @Test
    void verifiedMessageNamesTheSigner() {
        assertTrue(SignatureResult.verified("app", "gpg", "Signer", "FPR")
            .message().contains("Signer"));
        assertTrue(SignatureResult.verified("app", "gpg", "Signer", "FPR")
            .message().contains("FPR"));
    }
}
