package cz.cuni.mff.releasemanager.provider;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cz.cuni.mff.releasemanager.state.ProviderConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderHttpTest {

    private static final ProviderConfig CONFIG = new ProviderConfig(
        "github", "github", "https://github.com", "https://api.github.com", "PATH");

    @Test
    void followsRedirectsWithoutAllowingHttpsDowngrade() {
        ProviderHttp http = new ProviderHttp(
            CONFIG, "Test", "Authorization", "Bearer ");

        assertEquals(HttpClient.Redirect.NORMAL, http.client().followRedirects());
    }

    @Test
    void onlySendsTheTokenToKnownProviderHosts() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}");
        when(client.send(any(HttpRequest.class),
            eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);
        ProviderHttp http = new ProviderHttp(
            CONFIG, client, "GitHub", "Authorization", "Bearer ");

        http.get("https://api.github.com/repos/owner/repo", "api");
        http.get("https://evil.example/collect", "foreign");

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, org.mockito.Mockito.times(2)).send(
            requests.capture(), eq(HttpResponse.BodyHandlers.ofString()));
        assertEquals("Bearer " + System.getenv("PATH"),
            requests.getAllValues().get(0).headers()
                .firstValue("Authorization").orElse(null));
        assertNull(requests.getAllValues().get(1).headers()
            .firstValue("Authorization").orElse(null));
    }
}
