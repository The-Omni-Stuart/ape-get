package cz.cuni.mff.releasemanager.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.cmd.Command;
import cz.cuni.mff.releasemanager.json.JsonViews.SearchEntry;
import cz.cuni.mff.releasemanager.json.JsonViews.SearchError;
import cz.cuni.mff.releasemanager.json.JsonViews.SearchReport;
import cz.cuni.mff.releasemanager.json.JsonViews.SearchResults;
import cz.cuni.mff.releasemanager.provider.NetworkSettings;
import cz.cuni.mff.releasemanager.provider.ProviderNotices;
import cz.cuni.mff.releasemanager.provider.ProviderRegistry;
import cz.cuni.mff.releasemanager.provider.ProviderResolver;
import cz.cuni.mff.releasemanager.provider.ReleaseProvider;
import cz.cuni.mff.releasemanager.provider.SearchService;
import cz.cuni.mff.releasemanager.state.GrmConfig;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.SearchResult;
import cz.cuni.mff.releasemanager.utils.Output;

// the search command across configured providers
public final class SearchCommand {

    private final CmdParser cmdParser;
    private final SearchService searchService;
    private final GrmConfig toolConfig;
    private final ProviderRegistry providers;
    private final ProviderResolver resolveProvider;

    public SearchCommand(
        CmdParser cmdParser,
        SearchService searchService,
        GrmConfig toolConfig,
        ProviderRegistry providers,
        ProviderResolver resolveProvider) {
        this.cmdParser = cmdParser;
        this.searchService = searchService;
        this.toolConfig = toolConfig;
        this.providers = providers;
        this.resolveProvider = resolveProvider;
    }

    public boolean run(Command command) {
        String providerName = cmdParser.getProvider();
        if (providerName == null || providerName.isBlank()) {
            providerName = toolConfig.searchAllEnabled() ? "all" : "github";
        }

        if ("all".equalsIgnoreCase(providerName)) {
            List<ReleaseProvider> adapters = new ArrayList<>();
            for (ProviderConfig config : providers.all()) {
                ReleaseProvider provider = resolveProvider.resolve(config.name());
                if (provider != null) {
                    adapters.add(provider);
                }
            }
            adapters.sort(java.util.Comparator.comparing(provider -> provider.config().name()));
            if (adapters.isEmpty()) {
                Output.error("No providers are configured.");
                return false;
            }
            Output.info("Searching " + adapters.size() + " provider(s)...");
            return printSearchResults(searchService.searchAll(
                adapters, command.argument, NetworkSettings.apiTimeout()));
        }

        ReleaseProvider provider = resolveProvider.resolve(providerName);
        if (provider == null) {
            return false;
        }
        SearchResult result;
        try {
            result = provider.search(command.argument);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ProviderNotices.reportFailure(providerName, e);
            return false;
        }
        List<Repo> items = result.items() == null ? List.of() : result.items();
        if (cmdParser.isJson()) {
            List<SearchEntry> results = new ArrayList<>();
            for (Repo repo : items) {
                results.add(searchEntry(providerName, repo));
            }
            Output.json(new SearchResults(results));
            return true;
        }
        if (items.isEmpty()) {
            Output.data("No results found.");
            return true;
        }
        Output.data("Found " + items.size() + " repositories.");
        String prefix = "github".equalsIgnoreCase(providerName) ? "" : providerName + ":";
        for (Repo repo : items) {
            Output.data(prefix + repo.fullName() + ": " + repo.description());
        }
        Output.info("To install a release, use the 'install' command followed by the repository name.");
        return true;
    }

    private boolean printSearchResults(List<SearchService.ProviderResult> results) {
        List<SearchEntry> hits = new ArrayList<>();
        List<SearchError> errors = new ArrayList<>();
        int succeeded = 0;
        for (SearchService.ProviderResult result : results) {
            if (result.error() != null) {
                errors.add(new SearchError(result.provider(), result.error()));
                continue;
            }
            succeeded++;
            for (Repo repo : result.repos()) {
                hits.add(searchEntry(result.provider(), repo));
            }
        }
        if (cmdParser.isJson()) {
            Output.json(new SearchReport(hits, errors));
            return succeeded > 0;
        }
        ProviderNotices.reportFailures(results);
        if (succeeded == 0) {
            Output.error("All provider searches failed.");
            return false;
        }
        if (hits.isEmpty()) {
            Output.data("No results found.");
            return true;
        }
        for (SearchEntry hit : hits) {
            Output.data(hit.provider() + ":" + hit.repo() + ": "
                + (hit.description() == null ? "" : hit.description()));
        }
        Output.data("Found " + hits.size() + " repositories across "
            + succeeded + " provider(s).");
        Output.info("Use provider:owner/repo to install one of them.");
        return true;
    }

    private static SearchEntry searchEntry(String provider, Repo repo) {
        return new SearchEntry(provider, repo.fullName(), repo.description());
    }
}
