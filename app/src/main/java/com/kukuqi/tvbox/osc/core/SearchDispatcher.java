package com.kukuqi.tvbox.osc.core;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** A bounded batch with one terminal callback per source and a replaceable delivery epoch. */
public final class SearchDispatcher<T> implements AutoCloseable {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> daemon(runnable, "source-search"), new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledThreadPoolExecutor TIMERS = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "source-deadlines"));
    static { TIMERS.setRemoveOnCancelPolicy(true); WORKERS.allowCoreThreadTimeOut(true); }
    private final ExecutorService workers;
    private final ScheduledExecutorService timers;
    private final Executor delivery;
    private final long timeoutMs;
    private final int concurrency;
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Set<Job> active = new HashSet<>();
    private volatile long epoch;
    private Operation<T> operation;
    private BiConsumer<String, T> callback;
    @FunctionalInterface public interface Operation<T> { T run(String key) throws Exception; }
    public SearchDispatcher(Executor delivery, long timeoutMs) { this(WORKERS, TIMERS, delivery, timeoutMs, 4); }
    SearchDispatcher(ExecutorService workers, ScheduledExecutorService timers, Executor delivery, long timeoutMs, int concurrency) {
        this.workers = workers; this.timers = timers; this.delivery = delivery; this.timeoutMs = timeoutMs; this.concurrency = concurrency;
    }
    private static Thread daemon(Runnable runnable, String name) { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }
    public synchronized void start(List<String> sources, Operation<T> operation, BiConsumer<String, T> callback) {
        cancel();
        this.operation = operation; this.callback = callback;
        pending.addAll(new LinkedHashSet<>(sources));
        long current = epoch;
        timers.execute(() -> pump(current));
    }
    public synchronized void cancel() {
        epoch++;
        pending.clear();
        for (Job job : active) {
            if (job.task != null) job.task.cancel(true);
            if (job.deadline != null) job.deadline.cancel(false);
        }
        active.clear();
        if (workers instanceof ThreadPoolExecutor pool) pool.purge();
    }
    @Override public void close() { cancel(); }
    private synchronized void pump(long current) {
        while (current == epoch && active.size() < concurrency && !pending.isEmpty()) {
            Job job = new Job(current, pending.removeFirst(), callback);
            Operation<T> work = operation;
            active.add(job);
            try {
                job.task = workers.submit(() -> {
                    T value = null;
                    try { value = work.run(job.key); }
                    catch (Exception failure) { if (failure instanceof InterruptedException) Thread.currentThread().interrupt(); }
                    finally { T result = value; timers.execute(() -> finish(job, result)); }
                });
                job.deadline = timers.schedule(() -> {
                    job.task.cancel(true);
                    finish(job, null);
                }, timeoutMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException full) { timers.execute(() -> finish(job, null)); }
        }
    }
    private void finish(Job job, T value) {
        synchronized (this) {
            if (job.epoch != epoch || !active.remove(job)) return;
            if (job.deadline != null) job.deadline.cancel(false);
        }
        delivery.execute(() -> { if (epoch == job.epoch) job.callback.accept(job.key, value); });
        pump(job.epoch);
    }
    private final class Job {
        final long epoch;
        final String key;
        final BiConsumer<String, T> callback;
        Future<?> task;
        ScheduledFuture<?> deadline;
        Job(long epoch, String key, BiConsumer<String, T> callback) { this.epoch = epoch; this.key = key; this.callback = callback; }
    }
}
