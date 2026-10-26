package cz.cuni.mff.releasemanager.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderTypeTest {

    @Test
    void mapsUrlAndApiBasesBothWays() {
        assertEquals("https://api.github.com",
            ProviderType.GITHUB.apiBase("https://github.com", null));
        assertEquals("https://github.com",
            ProviderType.GITHUB.urlFromApi("https://api.github.com"));
        assertEquals("https://git.example/api/v1",
            ProviderType.GITEA.apiBase("https://git.example", null));
        assertEquals("https://git.example",
            ProviderType.GITEA.urlFromApi("https://git.example/api/v1"));
        assertEquals("https://gitlab.example/api/v4",
            ProviderType.GITLAB.apiBase("https://gitlab.example", null));
        assertEquals("https://gitlab.example",
            ProviderType.GITLAB.urlFromApi("https://gitlab.example/api/v4"));
        assertEquals("rns://7649a50d84610232d1416b41d2896aff",
            ProviderType.RNGIT.apiBase(
                "rns://7649a50d84610232d1416b41d2896aff", null));
        assertEquals("rns://7649a50d84610232d1416b41d2896aff",
            ProviderType.RNGIT.urlFromApi(
                "rns://7649a50d84610232d1416b41d2896aff"));
    }

    @Test
    void keepsAnExplicitApiBase() {
        assertEquals("https://api.example/custom",
            ProviderType.GITHUB.apiBase(
                "https://github.example", "https://api.example/custom/"));
    }
}
