package cz.cuni.mff.releasemanager.provider;

import java.io.IOException;
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
 * Runs provider calls concurrently under one deadline and keeps each failure.
 */
final class ProviderTasks {

    @FunctionalInterface
    interface Call<T> {
        T run() throws IOException, InterruptedException;
    }

    record Task<T>(String provider, Call<T> call) {}

    record Outcome<T>(
        String provider,
        T value,
        String error,
        ProviderException.Reason reason
    ) {}

    private ProviderTasks() {}

    static <T> List<Outcome<T>> run(List<Task<T>> tasks, Duration timeout) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        Map<String, Future<T>> futures = new LinkedHashMap<>();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (Task<T> task : tasks) {
                futures.put(task.provider(), executor.submit(task.call()::run));
            }

            List<Outcome<T>> outcomes = new ArrayList<>();
            long deadline = System.nanoTime() + timeout.toNanos();
            for (Map.Entry<String, Future<T>> entry : futures.entrySet()) {
                String name = entry.getKey();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    entry.getValue().cancel(true);
                    outcomes.add(new Outcome<>(name, null, "timed out",
                        ProviderException.Reason.TIMEOUT));
                    continue;
                }
                try {
                    T value = entry.getValue().get(remaining, TimeUnit.NANOSECONDS);
                    outcomes.add(new Outcome<>(name, value, null, null));
                } catch (TimeoutException e) {
                    entry.getValue().cancel(true);
                    outcomes.add(new Outcome<>(name, null, "timed out",
                        ProviderException.Reason.TIMEOUT));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    outcomes.add(new Outcome<>(
                        name,
                        null,
                        cause.getMessage() == null
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage(),
                        ProviderException.of(cause)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcomes.add(new Outcome<>(name, null, "interrupted",
                        ProviderException.Reason.OTHER));
                    break;
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }
}
