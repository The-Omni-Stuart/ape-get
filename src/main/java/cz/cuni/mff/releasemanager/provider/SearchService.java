package cz.cuni.mff.releasemanager.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.SearchResult;

/**
 * Searches several providers concurrently and keeps partial results.
 */
public final class SearchService {

    public record ProviderResult(
        String provider,
        List<Repo> repos,
        String error,
        ProviderException.Reason reason
    ) implements ProviderFailure {
        public ProviderResult(String provider, List<Repo> repos, String error) {
            this(provider, repos, error, error == null
                ? null : ProviderException.Reason.OTHER);
        }
    }

    /**
     * @param providers providers to query
     * @param query free-text query
     * @param timeout total timeout for the batch
     * @return one result per provider, in input order
     */
    public List<ProviderResult> searchAll(
        List<ReleaseProvider> providers, String query, Duration timeout) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        List<ProviderTasks.Task<SearchResult>> tasks = new ArrayList<>();
        for (ReleaseProvider provider : providers) {
            tasks.add(new ProviderTasks.Task<>(
                provider.config().name(), () -> provider.search(query)));
        }
        List<ProviderResult> results = new ArrayList<>();
        for (ProviderTasks.Outcome<SearchResult> outcome
            : ProviderTasks.run(tasks, timeout)) {
            List<Repo> repos = outcome.value() == null
                || outcome.value().items() == null
                ? List.of() : outcome.value().items();
            results.add(new ProviderResult(
                outcome.provider(), repos, outcome.error(), outcome.reason()));
        }
        return results;
    }
}
