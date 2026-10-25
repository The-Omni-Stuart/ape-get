package cz.cuni.mff.releasemanager.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import cz.cuni.mff.releasemanager.types.Repo;
import cz.cuni.mff.releasemanager.types.SearchResult;

/**
 * Searches several providers concurrently and keeps partial results.
 */
public final class SearchService {

    public record ProviderResult(String provider, List<Repo> repos, String error) {}

    /**
     * @param providers providers to query
     * @param query free-text query
     * @param timeout per-provider timeout
     * @return one result per provider, in input order
     */
    public List<ProviderResult> searchAll(
        List<ReleaseProvider> providers, String query, Duration timeout) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Map<ReleaseProvider, Future<SearchResult>> futures = new LinkedHashMap<>();
            for (ReleaseProvider provider : providers) {
                futures.put(provider, executor.submit(() -> provider.search(query)));
            }

            List<ProviderResult> results = new ArrayList<>();
            for (Map.Entry<ReleaseProvider, Future<SearchResult>> entry : futures.entrySet()) {
                String name = entry.getKey().config().name();
                try {
                    SearchResult result = entry.getValue().get(
                        timeout.toMillis(), TimeUnit.MILLISECONDS);
                    List<Repo> repos = result == null || result.items() == null
                        ? List.of()
                        : result.items();
                    results.add(new ProviderResult(name, repos, null));
                } catch (TimeoutException e) {
                    entry.getValue().cancel(true);
                    results.add(new ProviderResult(name, List.of(), "timed out"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    results.add(new ProviderResult(
                        name, List.of(), cause.getMessage() == null
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(new ProviderResult(name, List.of(), "interrupted"));
                    break;
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
