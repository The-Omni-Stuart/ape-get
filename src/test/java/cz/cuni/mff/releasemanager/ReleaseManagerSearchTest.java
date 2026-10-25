package cz.cuni.mff.releasemanager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.provider.SearchService;
import cz.cuni.mff.releasemanager.state.ConfigStore;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.SearchResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleaseManagerSearchTest extends ReleaseManagerTestSupport {

    @Test
    void testExecuteSearch() {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("github");

        var repo = new Repo("user/example", "Example repo");
        var searchResult = Optional.of(new SearchResult(List.of(repo)));

        when(mockGithubClient.searchRepoByName("example")).thenReturn(searchResult);

        assertEquals(0, releaseManager.execute(new String[] {"search", "example"}));

        verify(mockGithubClient).searchRepoByName("example");
    }

    @Test
    void testExecuteSearchUsesProviderOption() {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("github");
        when(mockGithubClient.searchRepoByName("example")).thenReturn(Optional.of(
            new SearchResult(List.of(new Repo("user/example", "Example repo")))));

        assertEquals(0, releaseManager.execute(
            new String[]{"search", "example", "--provider", "github"}));
        verify(mockGithubClient).searchRepoByName("example");
    }

    @Test
    void testExecuteSearchUnknownProviderFails() {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("work");

        assertEquals(1, releaseManager.execute(
            new String[]{"search", "example", "--provider", "work"}));
        verify(mockGithubClient, never()).searchRepoByName(any());
    }

    @Test
    void testExecuteSearchAllProviders() throws Exception {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("all");

        SearchService service = mock(SearchService.class);
        when(service.searchAll(any(), eq("example"), any())).thenReturn(List.of(
            new SearchService.ProviderResult(
                "github", List.of(new Repo("a/b", "A")), null),
            new SearchService.ProviderResult("codeberg", List.of(), "down")));
        useSearchService(service);

        assertEquals(0, releaseManager.execute(
            new String[]{"search", "example", "--provider", "all"}));
        verify(service).searchAll(any(), eq("example"), any());
    }

    @Test
    void testExecuteSearchDefaultsToAllProviders() throws Exception {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);

        SearchService service = mock(SearchService.class);
        when(service.searchAll(any(), eq("example"), any())).thenReturn(List.of(
            new SearchService.ProviderResult(
                "github", List.of(new Repo("a/b", "A")), null)));
        useSearchService(service);

        assertEquals(0, releaseManager.execute(new String[]{"search", "example"}));
        verify(service).searchAll(any(), eq("example"), any());
    }

    @Test
    void testExecuteSearchDefaultsToGithubWhenAllDisabled() throws Exception {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            new GrmConfig(
                GrmConfig.KIND,
                GrmConfig.SCHEMA_VERSION,
                false,
                1,
                null,
                false,
                Map.of(),
                false),
            false,
            null));
        when(mockGithubClient.searchRepoByName("example")).thenReturn(Optional.of(
            new SearchResult(List.of(new Repo("user/example", "Example repo")))));

        assertEquals(0, releaseManager.execute(new String[]{"search", "example"}));
        verify(mockGithubClient).searchRepoByName("example");
    }

    @Test
    void testExecuteSearchWithNoResultsSucceeds() {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("github");
        when(mockGithubClient.searchRepoByName("example")).thenReturn(Optional.empty());

        assertEquals(0, releaseManager.execute(new String[]{"search", "example"}));
    }

    @Test
    void testExecuteProvidersAddPreservesSearchAllFlag() throws Exception {
        Command cmd = Command.PROVIDERS;
        cmd.arguments = List.of("add", "work", "https://git.example.com", "--type", "gitea");
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockStateStore.loadConfig()).thenReturn(new ConfigStore.LoadResult(
            new GrmConfig(
                GrmConfig.KIND,
                GrmConfig.SCHEMA_VERSION,
                false,
                1,
                null,
                false,
                Map.of(),
                false),
            false,
            null));

        assertEquals(0, releaseManager.execute(new String[]{
            "providers", "add", "work", "https://git.example.com", "--type", "gitea"}));

        ArgumentCaptor<GrmConfig> captor = ArgumentCaptor.forClass(GrmConfig.class);
        verify(mockStateStore).saveConfig(captor.capture());
        assertFalse(captor.getValue().searchAllEnabled());
    }

    @Test
    void testExecuteSearchJson() throws Exception {
        Command cmd = Command.SEARCH;
        cmd.argument = "example";
        when(mockCmdParser.parse(any())).thenReturn(cmd);
        when(mockCmdParser.getProvider()).thenReturn("github");
        when(mockCmdParser.isJson()).thenReturn(true);
        when(mockGithubClient.searchRepoByName("example")).thenReturn(Optional.of(
            new SearchResult(List.of(new Repo("user/example", "Example repo")))));

        String output = captureOut(
            () -> releaseManager.execute(new String[]{"search", "example", "--json"}));

        JsonNode root = new ObjectMapper().readTree(output);
        assertEquals(1, root.get("results").size());
        assertEquals("github", root.get("results").get(0).get("provider").asText());
        assertEquals("user/example", root.get("results").get(0).get("repo").asText());
        assertEquals("Example repo", root.get("results").get(0).get("description").asText());
    }
}
