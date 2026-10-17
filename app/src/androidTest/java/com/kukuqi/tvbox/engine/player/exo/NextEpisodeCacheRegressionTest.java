package com.kukuqi.tvbox.engine.player.exo;

import android.os.Bundle;
import android.test.InstrumentationTestCase;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import com.kukuqi.tvbox.engine.player.media.MediaItemFactory;
import com.kukuqi.tvbox.engine.player.media.PlaySpec;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Proves next-episode bytes survive the old player's release and normal playback can read them. */
public class NextEpisodeCacheRegressionTest extends InstrumentationTestCase {
    private NanoHTTPD server;
    private String base;
    private final List<String> headers = Collections.synchronizedList(new ArrayList<>());
    @Override protected void setUp() throws Exception {
        super.setUp();
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                headers.add(session.getHeaders().get("x-episode"));
                if (!"next".equals(session.getHeaders().get("x-episode")) && !"current".equals(session.getHeaders().get("x-episode")))
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "missing authorization");
                try {
                    File file = new File("/sdcard/Download/tvbox-regression.mp4");
                    return newFixedLengthResponse(Response.Status.OK, "video/mp4", new FileInputStream(file), file.length());
                } catch (Exception e) { return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "fixture missing"); }
            }
        };
        server.start(); base = "http://127.0.0.1:" + server.getListeningPort();
    }
    @Override protected void tearDown() throws Exception { server.stop(); super.tearDown(); }
    public void testPreloadPersistsAndRecreatedPlaybackReadsCachedBytesOffline() throws Exception {
        assertTrue(new File("/sdcard/Download/tvbox-regression.mp4").isFile());
        String url = base + "/next.mp4?run=" + System.nanoTime();
        Bundle extras = new Bundle(); extras.putBoolean(ExoMediaSourceFactory.PRELOAD_CACHE_KEY, true);
        MediaItem item = MediaItemFactory.from(PlaySpec.from("next", url, new HashMap<>(Map.of("X-Episode", "next")), new MediaMetadata.Builder().setExtras(extras).build()));
        ExoPlayerSession[] session = {null};
        CountDownLatch complete = new CountDownLatch(1);
        try {
            onMain(() -> {
                session[0] = new ExoPlayerSession(0, new androidx.media3.common.Player.Listener(){}, null);
                try {
                    java.lang.reflect.Field field = ExoPlayerSession.class.getDeclaredField("preloadManager"); field.setAccessible(true);
                    ((androidx.media3.exoplayer.source.preload.DefaultPreloadManager) field.get(session[0])).addListener(new androidx.media3.exoplayer.source.preload.PreloadManagerListener() {
                        @Override public void onCompleted(MediaItem loaded) { complete.countDown(); }
                    });
                } catch (Exception e) { throw new RuntimeException(e); }
                session[0].preload(item, 0);
            });
            assertTrue("Preload did not finish", complete.await(15, TimeUnit.SECONDS));
            onMain(() -> session[0].release()); session[0] = null;
            // CacheDataSink commits a partial fragment when the preloader closes its source.
            long deadline = System.currentTimeMillis() + 5000;
            while (ExoMediaSourceFactory.getCache().getCachedBytes(url, 0, 65536) < 65536 && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertEquals("Released preload did not preserve reusable bytes", 65536, ExoMediaSourceFactory.getCache().getCachedBytes(url, 0, 65536));
            server.stop(); int requests = headers.size();
            DataSource cached = dataFactory(new ExoMediaSourceFactory(), Map.of("X-Episode", "next"), false).createDataSource();
            byte[] read = new byte[32768]; int offset = 0;
            try { cached.open(new DataSpec.Builder().setUri(url).setLength(read.length).build()); while (offset < read.length) { int n = cached.read(read, offset, read.length-offset); assertTrue(n > 0); offset += n; } }
            finally { cached.close(); }
            assertEquals(read.length, offset); assertEquals(requests, headers.size()); assertTrue(headers.contains("next"));
        } finally { if (session[0] != null) onMain(() -> session[0].release()); ExoMediaSourceFactory.getCache().removeResource(url); }
    }
    public void testCurrentAndNextAuthorizationFactoriesAreIndependent() throws Exception {
        ExoMediaSourceFactory factory = new ExoMediaSourceFactory();
        Map<String, String> original = new HashMap<>(Map.of("X-Episode", "current"));
        DataSource.Factory current = dataFactory(factory, original, false);
        DataSource.Factory next = dataFactory(factory, Map.of("X-Episode", "next"), false);
        original.put("X-Episode", "mutated");
        for (DataSource.Factory selected : List.of(current, next, current)) {
            DataSource source = selected.createDataSource();
            try { source.open(new DataSpec.Builder().setUri(base+"/auth.mp4").setLength(32).build()); assertEquals(32, source.read(new byte[32], 0, 32)); }
            finally { source.close(); }
        }
        assertEquals(List.of("current", "next", "current"), headers);
    }
    private DataSource.Factory dataFactory(ExoMediaSourceFactory factory, Map<String, String> headers, boolean writes) throws Exception {
        Method method = ExoMediaSourceFactory.class.getDeclaredMethod("getDataSourceFactory", Map.class, boolean.class); method.setAccessible(true);
        return (DataSource.Factory) method.invoke(factory, headers, writes);
    }
    private void onMain(Runnable action) { getInstrumentation().runOnMainSync(action); }
}
