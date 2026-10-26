package cz.cuni.mff.releasemanager.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        List<ProviderTasks.Task<ReleaseAssets>> tasks = new ArrayList<>();
        for (ReleaseProvider provider : providers) {
            String name = provider.config().name();
            results.put(name, null);
            if (!provider.supportsRepositoryLookup()) {
                results.put(name, new Result(name, false,
                    "repository lookup is not supported",
                    ProviderException.Reason.UNSUPPORTED));
                continue;
            }
            tasks.add(new ProviderTasks.Task<>(name, () -> provider.latest(path)));
        }

        for (ProviderTasks.Outcome<ReleaseAssets> outcome
            : ProviderTasks.run(tasks, timeout)) {
            if (outcome.reason() == ProviderException.Reason.NOT_FOUND) {
                results.put(outcome.provider(),
                    new Result(outcome.provider(), false, null));
            } else if (outcome.error() != null) {
                results.put(outcome.provider(), new Result(
                    outcome.provider(), false, outcome.error(), outcome.reason()));
            } else {
                results.put(outcome.provider(), new Result(
                    outcome.provider(), outcome.value() != null, null));
            }
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
