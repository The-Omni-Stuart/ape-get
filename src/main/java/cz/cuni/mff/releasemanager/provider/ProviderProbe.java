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

/**
 * Checks which providers host an exact repository path.
 */
public final class ProviderProbe {

    public record Result(
        String provider,
        boolean exists,
        String error,
        ProviderException.Reason reason
    ) implements ProviderFailure {
        public Result(String provider, boolean exists, String error) {
            this(provider, exists, error, error == null
                ? null : ProviderException.Reason.OTHER);
        }
    }

    public List<Result> probeAll(
        List<ReleaseProvider> providers, String path, Duration timeout) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }

        Map<String, Result> results = new LinkedHashMap<>();
        for (ReleaseProvider provider : providers) {
            results.put(provider.config().name(), null);
        }

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Map<ReleaseProvider, Future<ReleaseAssets>> futures = new LinkedHashMap<>();
            for (ReleaseProvider provider : providers) {
                String name = provider.config().name();
                if (!provider.supportsRepositoryLookup()) {
                    results.put(name, new Result(name, false,
                        "repository lookup is not supported",
                        ProviderException.Reason.UNSUPPORTED));
                    continue;
                }
                futures.put(provider, executor.submit(() -> provider.latest(path)));
            }

            long deadline = System.nanoTime() + timeout.toNanos();
            for (Map.Entry<ReleaseProvider, Future<ReleaseAssets>> entry
                : futures.entrySet()) {
                String name = entry.getKey().config().name();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    entry.getValue().cancel(true);
                    results.put(name, new Result(name, false, "timed out",
                        ProviderException.Reason.TIMEOUT));
                    continue;
                }
                try {
                    ReleaseAssets release = entry.getValue().get(
                        remaining, TimeUnit.NANOSECONDS);
                    results.put(name, new Result(name, release != null, null));
                } catch (TimeoutException e) {
                    entry.getValue().cancel(true);
                    results.put(name, new Result(name, false, "timed out",
                        ProviderException.Reason.TIMEOUT));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    ProviderException.Reason reason = ProviderException.of(cause);
                    if (reason == ProviderException.Reason.NOT_FOUND) {
                        results.put(name, new Result(name, false, null));
                    } else {
                        results.put(name, new Result(
                            name, false,
                            cause.getMessage() == null
                                ? cause.getClass().getSimpleName()
                                : cause.getMessage(),
                            reason));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.put(name, new Result(name, false, "interrupted",
                        ProviderException.Reason.OTHER));
                    break;
                }
            }
        } finally {
            executor.shutdownNow();
        }

        List<Result> ordered = new ArrayList<>();
        for (Result result : results.values()) {
            if (result != null) {
                ordered.add(result);
            }
        }
        return ordered;
    }
}
