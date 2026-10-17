package com.kukuqi.tvbox.osc;

import android.test.InstrumentationTestCase;
import androidx.lifecycle.Observer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kukuqi.tvbox.engine.api.config.VodConfig;
import com.kukuqi.tvbox.osc.bean.AbsXml;
import com.kukuqi.tvbox.osc.viewmodel.SourceViewModel;
import fi.iki.elonen.NanoHTTPD;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual HTTP collection sources through the host ViewModel, rather than an outer executor. */
public class SearchSchedulingRegressionTest extends InstrumentationTestCase {
    private NanoHTTPD server;
    private SourceViewModel model;
    private final AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
    private final CountDownLatch oldEntered = new CountDownLatch(1), releaseOld = new CountDownLatch(1);
    private String base;
    @Override protected void setUp() throws Exception {
        super.setUp();
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                String word = session.getParms().getOrDefault("wd", "");
                int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max);
                try {
                    if (word.equals("old")) { oldEntered.countDown(); releaseOld.await(5, TimeUnit.SECONDS); }
                    else Thread.sleep(200);
                    String response = "{\"page\":1,\"pagecount\":1,\"list\":[{\"vod_id\":\"1\",\"vod_name\":\""+word+"\",\"vod_pic\":\""+base+"/cover.jpg\"}]}";
                    return newFixedLengthResponse(Response.Status.OK, "application/json", response);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return newFixedLengthResponse(""); }
                finally { active.decrementAndGet(); }
            }
        };
        server.start(); base = "http://127.0.0.1:" + server.getListeningPort();
        JsonObject config = new JsonObject(); JsonArray sites = new JsonArray();
        for (int i = 0; i < 8; i++) {
            JsonObject site = new JsonObject(); site.addProperty("key", "batch"+i); site.addProperty("type", 1);
            site.addProperty("api", base + "/" + i); sites.add(site);
        }
        config.add("sites", sites); VodConfig.get().accept(base+"/config", "batch0", config);
        onMain(() -> model = new SourceViewModel());
    }
    @Override protected void tearDown() throws Exception { releaseOld.countDown(); onMain(() -> model.cancelSearch()); server.stop(); super.tearDown(); }
    public void testMultipleSourcesReallyRunConcurrentlyAndEveryResultArrives() throws Exception {
        CountDownLatch done = new CountDownLatch(8); Set<String> received = Collections.synchronizedSet(new HashSet<>());
        Observer<AbsXml> observer = value -> { if (value != null) received.add(value.movie.videoList.get(0).sourceKey); done.countDown(); };
        onMain(() -> { model.searchResult.observeForever(observer); model.startSearch(Arrays.asList("batch0","batch1","batch2","batch3","batch4","batch5","batch6","batch7"), "new", false); });
        try {
            assertTrue(done.await(15, TimeUnit.SECONDS)); assertEquals(8, received.size());
            assertTrue("Requests are still serial", maximum.get() >= 2); assertTrue("Unbounded HTTP concurrency", maximum.get() <= 4);
        } finally { onMain(() -> model.searchResult.removeObserver(observer)); }
    }
    public void testNewKeywordSuppressesLateOldResults() throws Exception {
        CountDownLatch done = new CountDownLatch(1); List<String> received = Collections.synchronizedList(new ArrayList<>());
        Observer<AbsXml> observer = value -> { received.add(value == null ? "failed" : value.movie.videoList.get(0).name); done.countDown(); };
        onMain(() -> { model.searchResult.observeForever(observer); model.startSearch(List.of("batch0"), "old", false); });
        try {
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS));
            onMain(() -> model.startSearch(List.of("batch1"), "new", false));
            assertTrue(done.await(8, TimeUnit.SECONDS)); releaseOld.countDown();
            Thread.sleep(400); getInstrumentation().waitForIdleSync();
            assertEquals(List.of("new"), received);
        } finally { onMain(() -> model.searchResult.removeObserver(observer)); }
    }
    private void onMain(Runnable action) { getInstrumentation().runOnMainSync(action); }
}
