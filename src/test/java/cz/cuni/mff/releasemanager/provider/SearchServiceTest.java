package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.SearchResult;

class SearchServiceTest {

    private static ReleaseProvider provider(String name) {
        ReleaseProvider provider = mock(ReleaseProvider.class);
        when(provider.config()).thenReturn(new ProviderConfig(
            name, "github", "https://github.com", "https://api.github.com", null));
        return provider;
    }

    @Test
    void aggregatesResultsWithProviderNames() throws Exception {
        ReleaseProvider one = provider("one");
        ReleaseProvider two = provider("two");
        when(one.search("q")).thenReturn(
            new SearchResult(List.of(new Repo("a/b", "A"))));
        when(two.search("q")).thenReturn(
            new SearchResult(List.of(new Repo("c/d", "C"))));

        List<SearchService.ProviderResult> results = new SearchService().searchAll(
            List.of(one, two), "q", Duration.ofSeconds(5));

        assertEquals(2, results.size());
        assertEquals("one", results.get(0).provider());
        assertEquals("a/b", results.get(0).repos().get(0).fullName());
        assertNull(results.get(0).error());
        assertEquals("two", results.get(1).provider());
    }

    @Test
    void keepsPartialResultsWhenOneProviderFails() throws Exception {
        ReleaseProvider failing = provider("failing");
        ReleaseProvider working = provider("working");
        when(failing.search("q")).thenThrow(new IOException("down"));
        when(working.search("q")).thenReturn(
            new SearchResult(List.of(new Repo("a/b", "A"))));

        List<SearchService.ProviderResult> results = new SearchService().searchAll(
            List.of(failing, working), "q", Duration.ofSeconds(5));

        assertEquals("down", results.get(0).error());
        assertEquals("a/b", results.get(1).repos().get(0).fullName());
    }

    @Test
    @Timeout(5)
    void timesOutSlowProviderWithoutFailingOthers() throws Exception {
        ReleaseProvider slow = provider("slow");
        ReleaseProvider fast = provider("fast");
        when(slow.search("q")).thenAnswer(invocation -> {
            Thread.sleep(2000);
            return new SearchResult(List.of());
        });
        when(fast.search("q")).thenReturn(
            new SearchResult(List.of(new Repo("a/b", "A"))));

        List<SearchService.ProviderResult> results = new SearchService().searchAll(
            List.of(fast, slow), "q", Duration.ofMillis(500));

        assertTrue(results.get(0).error() == null);
        assertNotNull(results.get(0).repos());
        assertEquals("timed out", results.get(1).error());
        assertEquals(ProviderException.Reason.TIMEOUT, results.get(1).reason());
    }
}
