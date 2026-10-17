package com.kukuqi.tvbox.osc.core;

import org.junit.After;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class EpisodePreloaderTest {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong clock = new AtomicLong();
    private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
    private final EpisodePreloader<String> preload = new EpisodePreloader<>(new SearchDispatcher<>(worker, timer, deliveries::add, 3000, 1), clock::get, 45000);
    private final EpisodePreloader.Key next = new EpisodePreloader.Key("site", "video", "line", "2");
    private void deliver() throws Exception { Runnable action = deliveries.poll(2, TimeUnit.SECONDS); assertNotNull(action); action.run(); }
    @After public void stop() throws Exception { preload.close(); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS); timer.shutdownNow(); }
    @Test public void resolvesOnceAndConsumesOnce() throws Exception {
        preload.request(next, () -> "resolved", value -> assertEquals("resolved", value)); deliver();
        preload.request(next, () -> { fail("Repeated progress must not resolve again"); return null; }, value -> {});
        assertEquals("resolved", preload.take(next)); assertNull(preload.take(next));
    }
    @Test public void identityIncludesSourceVideoLineAndEpisode() throws Exception {
        for (EpisodePreloader.Key other : new EpisodePreloader.Key[]{
                new EpisodePreloader.Key("other", "video", "line", "2"), new EpisodePreloader.Key("site", "other", "line", "2"),
                new EpisodePreloader.Key("site", "video", "other", "2"), new EpisodePreloader.Key("site", "video", "line", "3")}) {
            preload.request(next, () -> "resolved", value -> {}); deliver(); assertNull(preload.take(other));
        }
    }
    @Test public void expiredAndCancelledResultsCannotBeReused() throws Exception {
        preload.request(next, () -> "expired", value -> {}); deliver(); clock.set(45000); assertNull(preload.take(next));
        preload.request(next, () -> "cancelled", value -> fail("Cancelled ready callback"));
        Runnable old = deliveries.poll(2, TimeUnit.SECONDS); assertNotNull(old); preload.clear(); old.run(); assertNull(preload.take(next));
    }
    @Test public void replacingCandidateDropsDelayedResult() throws Exception {
        preload.request(next, () -> "old", value -> fail("Stale episode"));
        Runnable old = deliveries.poll(2, TimeUnit.SECONDS); assertNotNull(old);
        EpisodePreloader.Key fresh = new EpisodePreloader.Key("site", "video", "line", "3");
        preload.request(fresh, () -> "fresh", value -> {}); old.run(); deliver();
        assertEquals("fresh", preload.take(fresh));
    }
}
