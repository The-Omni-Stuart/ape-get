package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import cz.cuni.mff.releasemanager.GithubClient;
import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.SearchResult;
import cz.cuni.mff.releasemanager.utils.Output;

// the search command across configured providers
public final class SearchCommand {

    private final CmdParser cmdParser;
    private final GithubClient githubClient;
    private final SearchService searchService;
    private final GrmConfig toolConfig;
    private final ProviderRegistry providers;
    private final Function<String, ReleaseProvider> resolveProvider;
    private final Consumer<Object> printJson;

    public SearchCommand(
        CmdParser cmdParser,
        GithubClient githubClient,
        SearchService searchService,
        GrmConfig toolConfig,
        ProviderRegistry providers,
        Function<String, ReleaseProvider> resolveProvider,
        Consumer<Object> printJson) {
        this.cmdParser = cmdParser;
        this.githubClient = githubClient;
        this.searchService = searchService;
        this.toolConfig = toolConfig;
        this.providers = providers;
        this.resolveProvider = resolveProvider;
        this.printJson = printJson;
    }

    public boolean run(Command command) {
        String providerName = cmdParser.getProvider();
        if (providerName == null || providerName.isBlank()) {
            providerName = toolConfig.searchAllEnabled() ? "all" : "github";
        }

        if ("all".equalsIgnoreCase(providerName)) {
            List<ReleaseProvider> adapters = new ArrayList<>();
            for (ProviderConfig config : providers.all()) {
                ReleaseProvider provider = resolveProvider.apply(config.name());
                if (provider != null) {
                    adapters.add(provider);
                }
            }
            adapters.sort(java.util.Comparator.comparing(provider -> provider.config().name()));
            if (adapters.isEmpty()) {
                System.err.println("No providers are configured.");
                return false;
            }
            Output.info("Searching " + adapters.size() + " provider(s)...");
            return printSearchResults(searchService.searchAll(
                adapters, command.argument, Duration.ofSeconds(10)));
        }

        SearchResult result;
        if ("github".equalsIgnoreCase(providerName)) {
            var searchResult = githubClient.searchRepoByName(command.argument);
            if (searchResult.isEmpty()) {
                return false;
            }
            result = searchResult.get();
        } else {
            ReleaseProvider provider = resolveProvider.apply(providerName);
            if (provider == null) {
                return false;
            }
            try {
                result = provider.search(command.argument);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                System.err.println("Search failed: " + e.getMessage());
                return false;
            }
        }
        List<Repo> items = result.items() == null ? List.of() : result.items();
        if (cmdParser.isJson()) {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Repo repo : items) {
                results.add(searchEntry(providerName, repo));
            }
            printJson.accept(Map.of("results", results));
            return true;
        }
        if (items.isEmpty()) {
            System.out.println("No results found.");
            return true;
        }
        System.out.println("Found " + items.size() + " repositories.");
        String prefix = "github".equalsIgnoreCase(providerName) ? "" : providerName + ":";
        for (Repo repo : items) {
            System.out.println(prefix + repo.fullName() + ": " + repo.description());
        }
        Output.info("To install a release, use the 'install' command followed by the repository name.");
        return true;
    }

    private boolean printSearchResults(List<SearchService.ProviderResult> results) {
        List<Map<String, Object>> hits = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        int succeeded = 0;
        for (SearchService.ProviderResult result : results) {
            if (result.error() != null) {
                errors.add(Map.of(
                    "provider", result.provider(),
                    "error", result.error()));
                continue;
            }
            succeeded++;
            for (Repo repo : result.repos()) {
                hits.add(searchEntry(result.provider(), repo));
            }
        }
        if (cmdParser.isJson()) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("results", hits);
            report.put("errors", errors);
            printJson.accept(report);
            return succeeded > 0;
        }
        for (Map<String, Object> error : errors) {
            System.err.println("Warning: " + error.get("provider") + ": "
                + error.get("error"));
        }
        if (succeeded == 0) {
            System.err.println("All provider searches failed.");
            return false;
        }
        if (hits.isEmpty()) {
            System.out.println("No results found.");
            return true;
        }
        for (Map<String, Object> hit : hits) {
            System.out.println(hit.get("provider") + ":" + hit.get("repo") + ": "
                + (hit.get("description") == null ? "" : hit.get("description")));
        }
        System.out.println("Found " + hits.size() + " repositories across "
            + succeeded + " provider(s).");
        Output.info("Use provider:owner/repo to install one of them.");
        return true;
    }

    private static Map<String, Object> searchEntry(String provider, Repo repo) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("provider", provider);
        entry.put("repo", repo.fullName());
        entry.put("description", repo.description());
        return entry;
    }
}
