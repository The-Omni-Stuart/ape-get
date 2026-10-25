package cz.cuni.mff.releasemanager.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ChildEnvironmentTest {

    @Test
    void removesRegisteredTokenVariables() {
        ChildEnvironment.register("GRM_TEST_TOKEN");
        ProcessBuilder builder = new ProcessBuilder("true");
        builder.environment().put("GRM_TEST_TOKEN", "secret");
        builder.environment().put("GRM_KEEP_ME", "yes");

        ChildEnvironment.sanitize(builder);

        assertFalse(builder.environment().containsKey("GRM_TEST_TOKEN"));
        assertTrue(builder.environment().containsKey("GRM_KEEP_ME"));
    }
}
