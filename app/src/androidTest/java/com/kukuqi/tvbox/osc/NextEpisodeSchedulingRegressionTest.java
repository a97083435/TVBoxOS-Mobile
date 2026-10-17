package com.kukuqi.tvbox.osc;

import android.content.Context;
import android.test.InstrumentationTestCase;
import com.google.gson.JsonParser;
import com.kukuqi.tvbox.engine.api.SiteApi;
import com.kukuqi.tvbox.engine.api.config.VodConfig;
import com.kukuqi.tvbox.engine.bean.Result;
import com.kukuqi.tvbox.engine.player.extractor.Source;
import com.kukuqi.tvbox.osc.bean.VodInfo;
import com.kukuqi.tvbox.osc.core.EpisodePreloader;
import com.kukuqi.tvbox.osc.player.CorePlayer;
import com.kukuqi.tvbox.osc.player.MyVideoView;
import com.kukuqi.tvbox.osc.ui.fragment.PlayFragment;
import com.kukuqi.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;
import fi.iki.elonen.NanoHTTPD;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public class NextEpisodeSchedulingRegressionTest extends InstrumentationTestCase {
    private NanoHTTPD server;
    private String base;
    private final AtomicInteger calls = new AtomicInteger();
    private boolean existed, enabled;
    @Override protected void setUp() throws Exception {
        super.setUp(); existed = Hawk.contains(HawkConfig.NEXT_EPISODE_PRELOAD); enabled = Hawk.get(HawkConfig.NEXT_EPISODE_PRELOAD, true);
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                calls.incrementAndGet(); return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"parse\":0,\"url\":\""+base+"/next.mp4\",\"header\":{\"X-Episode\":\"next\"}}");
            }
        };
        server.start(); base = "http://127.0.0.1:" + server.getListeningPort();
        VodConfig.get().accept(base+"/config", "episodes", JsonParser.parseString("{\"sites\":[{\"key\":\"episodes\",\"type\":4,\"api\":\""+base+"/api\"}]}").getAsJsonObject());
    }
    @Override protected void tearDown() throws Exception {
        if (existed) Hawk.put(HawkConfig.NEXT_EPISODE_PRELOAD, enabled); else Hawk.delete(HawkConfig.NEXT_EPISODE_PRELOAD);
        server.stop(); super.tearDown();
    }
    public void testHostSchedulesOnlyNearEndAndReusesResolvedEpisode() throws Exception {
        PlayFragment fragment = new PlayFragment(); CorePlayer[] core = {null};
        VodInfo info = new VodInfo(); info.id = "video"; info.playFlag = "line";
        info.seriesMap = new LinkedHashMap<>(); info.seriesMap.put("line", Arrays.asList(new VodInfo.VodSeries("1", "first"), new VodInfo.VodSeries("2", "second")));
        try {
            onMain(() -> {
                try {
                    Hawk.put(HawkConfig.NEXT_EPISODE_PRELOAD, true);
                    core[0] = new CorePlayer(2, null); core[0].initPlayer();
                    set(fragment, "mVideoView", new PlayingView(getInstrumentation().getTargetContext(), core[0]));
                    set(fragment, "mVodInfo", info); set(fragment, "sourceKey", "episodes"); set(fragment, "mVodPlayerCfg", new JSONObject());
                    set(fragment, "corePlayResult", Result.fromJson("{\"parse\":0,\"url\":\""+base+"/current.mp4\"}"));
                    progress(fragment, 90000, 10000);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertEquals(0, calls.get());
            onMain(() -> { try { progress(fragment, 90000, 65000); progress(fragment, 90000, 66000); } catch (Exception e) { throw new RuntimeException(e); } });
            long deadline = System.currentTimeMillis() + 8000;
            Object[] pending = {null};
            do {
                onMain(() -> { try { pending[0] = get(core[0].manager(), "pendingPreload"); } catch (Exception e) { throw new RuntimeException(e); } });
                if (pending[0] == null) Thread.sleep(50);
            } while (pending[0] == null && System.currentTimeMillis() < deadline);
            assertNotNull("Host never scheduled engine preload", pending[0]); assertEquals(1, calls.get());
            EpisodePreloader<Result> cache = (EpisodePreloader<Result>) get(fragment, "nextEpisode");
            Result resolved = cache.take(new EpisodePreloader.Key("episodes", "video", "line", "second"));
            assertNotNull(resolved); assertEquals(base+"/next.mp4", resolved.getUrl().v()); assertEquals("next", resolved.getHeader().get("X-Episode"));
            onMain(() -> { try { progress(fragment, 90000, 10000); assertNull(get(core[0].manager(), "pendingPreload")); } catch (Exception e) { throw new RuntimeException(e); } });
            info.playIndex = 1;
            onMain(() -> { try { progress(fragment, 90000, 65000); } catch (Exception e) { throw new RuntimeException(e); } });
            assertEquals("Last episode must not request another", 1, calls.get());
        } finally {
            ((EpisodePreloader<?>) get(fragment, "nextEpisode")).close();
            if (core[0] != null) onMain(() -> core[0].release());
        }
    }
    public void testBackgroundResolutionDoesNotStartOrStopSharedExtractor() throws Exception {
        Field field = Source.class.getDeclaredField("extractors"); field.setAccessible(true);
        List<Source.Extractor> extractors = (List<Source.Extractor>) field.get(Source.get());
        AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        Source.Extractor guard = new Source.Extractor() {
            public String fetch(String url) { starts.incrementAndGet(); return url; }
            public boolean match(android.net.Uri uri) { return uri.toString().equals(base+"/next.mp4"); }
            public void stop() { stops.incrementAndGet(); }
            public void exit() {}
        };
        extractors.add(0, guard);
        try {
            Result result = SiteApi.preloadContent("episodes", "line", "second");
            assertEquals(0, starts.get()); assertEquals(0, stops.get()); assertTrue(Source.get().requiresExtraction(result));
            SiteApi.playerContent("episodes", "line", "second"); assertEquals(1, starts.get()); assertEquals(1, stops.get());
        } finally { extractors.remove(guard); }
    }
    private static class PlayingView extends MyVideoView {
        private final CorePlayer core;
        PlayingView(Context context, CorePlayer core) { super(context); this.core = core; }
        @Override public boolean isPlaying() { return true; }
        @Override public xyz.doikki.videoplayer.player.AbstractPlayer getMediaPlayer() { return core; }
    }
    private static void progress(PlayFragment fragment, long duration, long position) throws Exception {
        Method method = PlayFragment.class.getDeclaredMethod("preloadNext", long.class, long.class); method.setAccessible(true); method.invoke(fragment, duration, position);
    }
    private static Object get(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static void set(Object target, String name, Object value) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
    private void onMain(Runnable action) { getInstrumentation().runOnMainSync(action); }
}
