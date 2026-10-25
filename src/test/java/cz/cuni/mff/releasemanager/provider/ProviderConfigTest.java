package cz.cuni.mff.releasemanager.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ProviderConfigTest {

    @Test
    void rejectsATokenVariableThatCannotBeRead() {
        assertFalse(config("A=B").isValid());
        assertFalse(config("A B").isValid());
        assertTrue(config("GITEA_TOKEN").isValid());
        assertTrue(config(null).isValid());
    }

    private static ProviderConfig config(String tokenEnv) {
        return new ProviderConfig("gitea", "https://git.example.com",
            "https://git.example.com/api/v1", tokenEnv);
    }
}
