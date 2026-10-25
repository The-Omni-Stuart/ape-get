package cz.cuni.mff.releasemanager.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class RepoRefTest {

    @Test
    void rejectsBarePath() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class, () -> RepoRef.parse("owner/repo"));

        assertTrue(error.getMessage().contains("provider"));
    }

    @Test
    void parsesProviderPrefix() {
        RepoRef ref = RepoRef.parse("work:team/app");

        assertEquals("work", ref.provider());
        assertEquals("team/app", ref.path());
    }

    @Test
    void allowsGitlabSubgroups() {
        RepoRef ref = RepoRef.parse("gitlab:group/sub/project");

        assertEquals("gitlab", ref.provider());
        assertEquals("group/sub/project", ref.path());
    }

    @Test
    void rejectsMalformedReferences() {
        assertThrows(IllegalArgumentException.class, () -> RepoRef.parse("owner"));
        assertThrows(IllegalArgumentException.class, () -> RepoRef.parse("owner//repo"));
        assertThrows(IllegalArgumentException.class, () -> RepoRef.parse("owner/repo:tag"));
        assertThrows(IllegalArgumentException.class, () -> RepoRef.parse(":owner/repo"));
        assertThrows(IllegalArgumentException.class, () -> RepoRef.parse("../repo"));
        assertThrows(IllegalArgumentException.class, () -> RepoRef.parse(null));
    }
}
