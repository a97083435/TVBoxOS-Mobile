package com.kukuqi.tvbox.osc.core;

import org.junit.After;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SearchDispatcherTest {
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private SearchDispatcher<String> dispatcher;
    private SearchDispatcher<String> create(Executor delivery, long timeout, int concurrency) {
        return dispatcher = new SearchDispatcher<>(workers, timers, delivery, timeout, concurrency);
    }
    @After public void stop() throws Exception {
        if (dispatcher != null) dispatcher.close();
        workers.shutdownNow(); workers.awaitTermination(3, TimeUnit.SECONDS); timers.shutdownNow();
    }
    @Test public void slowSourceDoesNotBlockOthersAndConcurrencyIsBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1), fast = new CountDownLatch(1), done = new CountDownLatch(8);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        create(Runnable::run, 3000, 4).start(Arrays.asList("slow", "1", "2", "3", "4", "5", "6", "7"), key -> {
            int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max); entered.countDown();
            try { if (key.equals("slow")) release.await(); else { assertTrue(entered.await(2, TimeUnit.SECONDS)); fast.countDown(); } return key; }
            finally { active.decrementAndGet(); }
        }, (key, value) -> done.countDown());
        assertTrue(fast.await(2, TimeUnit.SECONDS)); assertEquals(4, maximum.get());
        release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS));
    }
    @Test public void timeoutEmitsOnceAndStartsQueuedSources() throws Exception {
        CountDownLatch done = new CountDownLatch(3), interrupted = new CountDownLatch(1);
        Map<String, String> values = Collections.synchronizedMap(new HashMap<>());
        create(Runnable::run, 100, 1).start(Arrays.asList("slow", "ok", "ok", "fail"), key -> {
            if (key.equals("slow")) { try { new CountDownLatch(1).await(); } catch (InterruptedException e) { interrupted.countDown(); throw e; } }
            if (key.equals("fail")) throw new IllegalStateException("offline");
            return key;
        }, (key, value) -> { values.put(key, value); done.countDown(); });
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertEquals(3, values.size()); assertNull(values.get("slow")); assertNull(values.get("fail")); assertEquals("ok", values.get("ok"));
    }
    @Test public void replacementDropsAlreadyQueuedDeliveries() throws Exception {
        BlockingQueue<Runnable> delivery = new LinkedBlockingQueue<>(); List<String> received = new ArrayList<>();
        create(delivery::add, 2000, 1).start(List.of("old"), key -> key, (key, value) -> received.add(value));
        Runnable old = delivery.poll(2, TimeUnit.SECONDS); assertNotNull(old);
        dispatcher.start(List.of("new"), key -> key, (key, value) -> received.add(value));
        Runnable fresh = delivery.poll(2, TimeUnit.SECONDS); assertNotNull(fresh);
        old.run(); fresh.run(); assertEquals(List.of("new"), received);
    }
    @Test public void cancelInterruptsActiveAndNeverStartsPending() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        create(Runnable::run, 2000, 1).start(List.of("old", "pending"), key -> {
            calls.incrementAndGet(); entered.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException e) { interrupted.countDown(); throw e; }
            return key;
        }, (key, value) -> fail("Cancelled results must not be delivered"));
        assertTrue(entered.await(2, TimeUnit.SECONDS)); dispatcher.cancel();
        assertTrue(interrupted.await(2, TimeUnit.SECONDS)); assertEquals(1, calls.get());
    }
    @Test public void sameSiteWaitIsInterruptibleWhileOtherSiteCanRun() throws Exception {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), waiting = new CountDownLatch(1);
        Future<?> first = workers.submit(() -> { try { SourceAccess.call("exclusive", () -> { held.countDown(); release.await(); return null; }); } catch (Exception e) { throw new RuntimeException(e); } });
        assertTrue(held.await(2, TimeUnit.SECONDS));
        Future<String> second = workers.submit(() -> { waiting.countDown(); return SourceAccess.call("exclusive", () -> { fail("Locked source entered"); return "bad"; }); });
        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        assertEquals("other", workers.submit(() -> SourceAccess.call("other", () -> "other")).get(2, TimeUnit.SECONDS));
        assertTrue(second.cancel(true)); release.countDown(); first.get(2, TimeUnit.SECONDS);
        assertEquals("released", SourceAccess.call("exclusive", () -> "released"));
    }
}
