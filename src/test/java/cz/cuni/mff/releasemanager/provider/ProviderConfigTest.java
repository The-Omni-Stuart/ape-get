package cz.cuni.mff.releasemanager.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ProviderConfigTest {

    @Test
    void acceptsRngitDestinationsAndAliases() {
        assertTrue(rngit("rns://7649a50d84610232d1416b41d2896aff").isValid());
        assertTrue(rngit("rns://reticulum").isValid());
        assertFalse(rngit("https://example.com").isValid());
        assertFalse(rngit("rns://reticulum/group").isValid());
    }

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

    private static ProviderConfig rngit(String url) {
        return new ProviderConfig("node", "rngit", url, null, null);
    }
}
