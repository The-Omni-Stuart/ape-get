package cz.cuni.mff.releasemanager.provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.state.ProviderConfig;
import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.utils.Output;

/**
 * Turns an install argument into a provider-qualified repository reference.
 */
public final class InstallSourceResolver {
    private final CmdParser cmdParser;
    private final ProviderRegistry providers;
    private final ProviderResolver resolveProvider;
    private final SearchService searchService;
    private final ProviderProbe providerProbe;

    public InstallSourceResolver(
        CmdParser cmdParser,
        ProviderRegistry providers,
        ProviderResolver resolveProvider,
        SearchService searchService,
        ProviderProbe providerProbe) {
        this.cmdParser = cmdParser;
        this.providers = providers;
        this.resolveProvider = resolveProvider;
        this.searchService = searchService;
        this.providerProbe = providerProbe;
    }

    /**
     * @param argument provider:path, owner/repo, or a bare name
     * @return provider-qualified reference, the unchanged input, or null on rejection
     */
    public String resolve(String argument) {
        String value = argument == null ? "" : argument.strip();
        if (value.isEmpty() || value.contains(":")) {
            return argument;
        }
        String path = trimSlashes(value);
        if (path.isEmpty() || path.contains("//") || path.contains("\\")) {
            Output.error("Invalid repository: " + argument);
            return null;
        }

        String requested = cmdParser.getProvider();
        if (requested != null && !requested.isBlank()) {
            if (!path.contains("/")) {
                Output.error("--provider needs an owner/repo argument.");
                return null;
            }
            return requested + ":" + path;
        }

        List<ReleaseProvider> adapters = searchableProviders();
        if (adapters.isEmpty()) {
            Output.error("No providers are configured; use provider:owner/repo.");
            return null;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        Output.info("Looking for " + path + " across " + adapters.size()
            + " provider(s)...");
        Map<String, SourceOption> found = new LinkedHashMap<>();
        if (path.contains("/")) {
            List<ProviderProbe.Result> results = providerProbe.probeAll(
                adapters, path, NetworkSettings.apiTimeout());
            ProviderNotices.reportFailures(results);
            for (ProviderProbe.Result result : results) {
                if (result.exists()) {
                    found.putIfAbsent(result.provider() + ":"
                        + path.toLowerCase(Locale.ROOT),
                        new SourceOption(result.provider(), path, null));
                }
            }
        } else {
            List<SearchService.ProviderResult> results = searchService.searchAll(
                adapters, name, NetworkSettings.apiTimeout());
            ProviderNotices.reportFailures(results);
            for (SearchService.ProviderResult result : results) {
                if (result.error() != null || result.repos() == null) {
                    continue;
                }
                for (Repo repo : result.repos()) {
                    if (repo.fullName() == null || !matches(repo.fullName(), path)) {
                        continue;
                    }
                    String key = result.provider() + ":"
                        + repo.fullName().toLowerCase(Locale.ROOT);
                    found.putIfAbsent(key, new SourceOption(
                        result.provider(), repo.fullName(), repo.description()));
                }
            }
        }
        List<SourceOption> candidates = List.copyOf(found.values());
        if (candidates.isEmpty()) {
            String prefix = path.contains("/")
                ? "No repository with a published release matching '"
                : "No repository matching '";
            Output.error(prefix + argument + "'; try 'search "
                + name + "' or use provider:owner/repo.");
            return null;
        }
        if (candidates.size() == 1) {
            SourceOption only = candidates.get(0);
            String resolved = only.provider() + ":" + only.path();
            Output.info("Using " + resolved + ".");
            return resolved;
        }

        SourceOption chosen = chooseSource(path, candidates);
        return chosen == null ? null : chosen.provider() + ":" + chosen.path();
    }

    private SourceOption chooseSource(String path, List<SourceOption> candidates) {
        Output.error("Multiple sources found for '" + path + "':");
        for (int i = 0; i < candidates.size(); i++) {
            Output.error("  " + (i + 1) + ": " + candidates.get(i).label());
        }
        if (cmdParser.isJson() || cmdParser.isAssumeYes() || System.console() == null) {
            Output.error("Install with provider:owner/repo to choose one.");
            return null;
        }
        Output.error("Choose a source [1-" + candidates.size() + "]:");
        while (true) {
            int choice = cmdParser.getUserInput();
            if (choice < 0) {
                Output.error("No source selected.");
                return null;
            }
            if (choice >= 1 && choice <= candidates.size()) {
                return candidates.get(choice - 1);
            }
            Output.error("Invalid choice. Valid options are: 1 - "
                + candidates.size());
        }
    }

    private List<ReleaseProvider> searchableProviders() {
        List<ReleaseProvider> adapters = new ArrayList<>();
        for (ProviderConfig config : providers.all()) {
            ReleaseProvider provider = resolveProvider.resolve(config.name());
            if (provider != null) {
                adapters.add(provider);
            }
        }
        adapters.sort(Comparator.comparing(provider -> provider.config().name()));
        return adapters;
    }

    private static boolean matches(String fullName, String query) {
        if (query.contains("/")) {
            return fullName.equalsIgnoreCase(query);
        }
        int slash = fullName.lastIndexOf('/');
        return slash >= 0
            && fullName.substring(slash + 1).equalsIgnoreCase(query);
    }

    private static String trimSlashes(String value) {
        String trimmed = value;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record SourceOption(String provider, String path, String description) {
        String label() {
            return provider + ":" + path
                + (description == null || description.isBlank()
                    ? "" : " - " + description);
        }
    }
}
