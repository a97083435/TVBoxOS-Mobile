package com.kukuqi.tvbox.osc.core;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Keeps only the immediate next episode, including the source/video/line identity and a short TTL. */
public final class EpisodePreloader<T> implements AutoCloseable {
    public record Key(String source, String video, String line, String url) {}
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), runnable -> { Thread t = new Thread(runnable, "episode-resolve"); t.setDaemon(true); return t; });
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread t = new Thread(runnable, "episode-deadline"); t.setDaemon(true); return t;
    });
    static { TIMER.setRemoveOnCancelPolicy(true); }
    private final SearchDispatcher<T> requests;
    private final LongSupplier clock;
    private final long ttlMs;
    private Key key;
    private T value;
    private long created;
    public EpisodePreloader(Executor delivery) {
        this(new SearchDispatcher<>(WORKER, TIMER, delivery, 15000, 1), () -> System.nanoTime() / 1_000_000, 45000);
    }
    EpisodePreloader(SearchDispatcher<T> requests, LongSupplier clock, long ttlMs) {
        this.requests = requests; this.clock = clock; this.ttlMs = ttlMs;
    }
    public synchronized void request(Key candidate, Callable<T> resolver, Consumer<T> ready) {
        if (candidate.equals(key) && clock.getAsLong() - created < ttlMs) return;
        clear();
        key = candidate; created = clock.getAsLong();
        requests.start(List.of(candidate.url()), ignored -> resolver.call(), (ignored, result) -> {
            synchronized (this) {
                if (!candidate.equals(key) || clock.getAsLong() - created >= ttlMs) return;
                value = result;
                if (result != null) ready.accept(result);
            }
        });
    }
    public synchronized T take(Key candidate) {
        T result = candidate.equals(key) && clock.getAsLong() - created < ttlMs ? value : null;
        clear();
        return result;
    }
    public synchronized void clear() { requests.cancel(); key = null; value = null; }
    @Override public void close() { clear(); }
}
