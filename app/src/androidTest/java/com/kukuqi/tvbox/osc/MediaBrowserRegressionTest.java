package com.kukuqi.tvbox.osc;

import android.content.*;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.test.InstrumentationTestCase;
import com.kukuqi.tvbox.osc.api.ApiConfig;
import com.kukuqi.tvbox.osc.bean.*;
import com.kukuqi.tvbox.osc.cache.*;
import com.kukuqi.tvbox.osc.data.AppDataManager;
import com.kukuqi.tvbox.osc.service.*;
import com.kukuqi.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

public class MediaBrowserRegressionTest extends InstrumentationTestCase {
    private MediaCatalog catalog;
    private Object oldPrivate, oldLive;
    private Map<String, SourceBean> sources;
    private SourceBean before;
    private static final String KEY = "media-regression", ID = "vod-regression";
    @Override protected void setUp() throws Exception {
        super.setUp(); oldPrivate = Hawk.contains(HawkConfig.PRIVATE_BROWSING) ? Hawk.get(HawkConfig.PRIVATE_BROWSING) : null;
        oldLive = Hawk.contains(HawkConfig.LIVE_URL) ? Hawk.get(HawkConfig.LIVE_URL) : null;
        Field field = ApiConfig.class.getDeclaredField("sourceBeanList"); field.setAccessible(true); sources = (Map<String, SourceBean>) field.get(ApiConfig.get());
        SourceBean source = new SourceBean(); source.setKey(KEY); source.setName("媒体测试"); before = sources.put(KEY, source);
        catalog = new MediaCatalog(getInstrumentation().getTargetContext()); Hawk.put(HawkConfig.PRIVATE_BROWSING, false);
    }
    @Override protected void tearDown() throws Exception {
        catalog.close();
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(KEY, ID); if (record != null) AppDataManager.get().getVodRecordDao().delete(record);
        VodCollect collect = AppDataManager.get().getVodCollectDao().getVodCollect(KEY, ID); if (collect != null) AppDataManager.get().getVodCollectDao().delete(collect);
        if (before == null) sources.remove(KEY); else sources.put(KEY, before);
        if (oldPrivate == null) Hawk.delete(HawkConfig.PRIVATE_BROWSING); else Hawk.put(HawkConfig.PRIVATE_BROWSING, oldPrivate);
        if (oldLive == null) Hawk.delete(HawkConfig.LIVE_URL); else Hawk.put(HawkConfig.LIVE_URL, oldLive);
        super.tearDown();
    }
    public void testRealMediaBrowserConnectsAndSubscribesToRoot() throws Exception {
        CountDownLatch loaded = new CountDownLatch(1); List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        MediaBrowserCompat[] browser = {null};
        getInstrumentation().runOnMainSync(() -> {
            browser[0] = new MediaBrowserCompat(getInstrumentation().getTargetContext(), new ComponentName(getInstrumentation().getTargetContext(), PlayService.class), new MediaBrowserCompat.ConnectionCallback() {
                @Override public void onConnected() { browser[0].subscribe(browser[0].getRoot(), new MediaBrowserCompat.SubscriptionCallback() {
                    @Override public void onChildrenLoaded(String id, List<MediaBrowserCompat.MediaItem> children) { items.addAll(children); loaded.countDown(); }
                }); }
                @Override public void onConnectionFailed() { loaded.countDown(); }
            }, null); browser[0].connect();
        });
        try { assertTrue(loaded.await(8, TimeUnit.SECONDS)); assertEquals(3, items.size()); assertTrue(items.stream().allMatch(MediaBrowserCompat.MediaItem::isBrowsable)); }
        finally { getInstrumentation().runOnMainSync(() -> browser[0].disconnect()); }
    }
    public void testHistoryFavoritesPrivacyAndDeletedRecordsAreValidated() throws Exception {
        VodInfo info = new VodInfo(); info.id = ID; info.name = "继续观看"; info.playNote = "第2集"; RoomDataManger.insertVodRecord(KEY, info);
        List<MediaBrowserCompat.MediaItem> history = children(MediaCatalog.HISTORY);
        var movie = history.stream().filter(item -> item.getDescription().getTitle().toString().equals("继续观看")).findFirst().orElseThrow();
        Intent target = catalog.intent(movie.getMediaId()); assertNotNull(target); assertEquals(ID, target.getStringExtra("id")); assertEquals(KEY, target.getStringExtra("sourceKey"));
        Hawk.put(HawkConfig.PRIVATE_BROWSING, true); assertTrue(children(MediaCatalog.HISTORY).isEmpty()); assertNull(catalog.intent(movie.getMediaId()));
        RoomDataManger.insertVodCollect(KEY, info); var favorites = children(MediaCatalog.FAVORITES); assertTrue(favorites.stream().anyMatch(item -> item.getMediaId().equals(movie.getMediaId()))); assertNotNull(catalog.intent(movie.getMediaId()));
        RoomDataManger.deleteVodCollect(KEY, info); RoomDataManger.deleteVodRecord(KEY, info); assertNull(catalog.intent(movie.getMediaId()));
    }
    public void testLiveDirectoryHidesPasswordGroupsAndRejectsStaleSource() throws Exception {
        File playlist = new File(getInstrumentation().getTargetContext().getCacheDir(), "media-browser-live.json");
        try (FileWriter writer = new FileWriter(playlist)) { writer.write("[{\"group\":\"公开\",\"channels\":[{\"name\":\"测试台\",\"urls\":[\"http://example.org/stream.m3u8\"]}]},{\"group\":\"加密_123\",\"channels\":[{\"name\":\"隐藏台\",\"urls\":[\"http://example.org/private\"]}]}]"); }
        try {
            String source = android.net.Uri.fromFile(playlist).toString(); Hawk.put(HawkConfig.LIVE_URL, source);
            List<MediaBrowserCompat.MediaItem> groups = children(MediaCatalog.LIVE); assertEquals(1, groups.size()); assertEquals("公开", groups.get(0).getDescription().getTitle());
            List<MediaBrowserCompat.MediaItem> channels = children(groups.get(0).getMediaId()); assertEquals(1, channels.size());
            String id = channels.get(0).getMediaId(); Intent target = catalog.intent(id); assertNotNull(target); assertEquals("测试台", target.getStringExtra(MediaCatalog.EXTRA_LIVE_CHANNEL)); assertFalse(id.contains("stream.m3u8"));
            Hawk.put(HawkConfig.LIVE_URL, source+"?changed"); assertNull(catalog.intent(id));
        } finally { playlist.delete(); }
    }
    public void testLiveSubscriptionDoesNotReloadOnMetadataNotification() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        fi.iki.elonen.NanoHTTPD server = new fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                requests.incrementAndGet(); return newFixedLengthResponse(Response.Status.OK, "application/json", "[{\"group\":\"公开\",\"channels\":[{\"name\":\"测试台\",\"urls\":[\"http://example.org/live.mp4\"]}]}]");
            }
        };
        server.start(); Hawk.put(HawkConfig.LIVE_URL, "http://127.0.0.1:"+server.getListeningPort()+"/live.json");
        CountDownLatch loaded = new CountDownLatch(1); MediaBrowserCompat[] browser = {null};
        try {
            getInstrumentation().runOnMainSync(() -> {
                browser[0] = new MediaBrowserCompat(getInstrumentation().getTargetContext(), new ComponentName(getInstrumentation().getTargetContext(), PlayService.class), new MediaBrowserCompat.ConnectionCallback() {
                    @Override public void onConnected() { browser[0].subscribe(MediaCatalog.LIVE, new MediaBrowserCompat.SubscriptionCallback() {
                        @Override public void onChildrenLoaded(String id, List<MediaBrowserCompat.MediaItem> children) { if (!children.isEmpty()) loaded.countDown(); }
                    }); }
                }, null); browser[0].connect();
            });
            assertTrue(loaded.await(8, TimeUnit.SECONDS));
            getInstrumentation().runOnMainSync(() -> org.greenrobot.eventbus.EventBus.getDefault().post(new com.kukuqi.tvbox.osc.event.SourceChangedEvent()));
            Thread.sleep(1000);
            assertEquals("Metadata notification reloaded the live subscription", 1, requests.get());
        } finally {
            getInstrumentation().runOnMainSync(() -> { if (browser[0] != null) browser[0].disconnect(); }); server.stop();
        }
    }
    public void testBrowserPlayDispatchWorksWithoutPreviewAndSwitchesOpenDetail() throws Exception {
        String link = com.kukuqi.tvbox.osc.ui.dialog.VideoLinkDialog.SOURCE;
        SourceBean source = new SourceBean(); source.setKey(link); source.setName("链接播放");
        SourceBean previous = sources.put(link, source);
        Object preview = Hawk.contains(HawkConfig.SHOW_PREVIEW) ? Hawk.get(HawkConfig.SHOW_PREVIEW) : null;
        Object backend = Hawk.contains(HawkConfig.PLAY_TYPE) ? Hawk.get(HawkConfig.PLAY_TYPE) : null;
        boolean normal = com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart;
        fi.iki.elonen.NanoHTTPD server = new fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                try { File file = new File("/sdcard/TVboxOSC-regression/tvbox-regression.mp4"); return newFixedLengthResponse(Response.Status.OK, "video/mp4", new FileInputStream(file), file.length()); }
                catch (IOException e) { return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing fixture"); }
            }
        };
        server.start();
        VodInfo first = new VodInfo(), second = new VodInfo();
        first.id = "http://127.0.0.1:"+server.getListeningPort()+"/video.mp4?item=1"; first.name = "系统媒体测试一";
        second.id = "http://127.0.0.1:"+server.getListeningPort()+"/video.mp4?item=2"; second.name = "系统媒体测试二";
        MediaBrowserCompat[] browser = {null};
        android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(com.kukuqi.tvbox.osc.ui.activity.DetailActivity.class.getName(), null, false);
        com.kukuqi.tvbox.osc.ui.activity.DetailActivity detail = null;
        try {
            Hawk.put(HawkConfig.SHOW_PREVIEW, false); Hawk.put(HawkConfig.PLAY_TYPE, 2); com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart = true;
            RoomDataManger.insertVodCollect(link, first); RoomDataManger.insertVodCollect(link, second);
            CountDownLatch ready = new CountDownLatch(1); List<MediaBrowserCompat.MediaItem> favorites = Collections.synchronizedList(new ArrayList<>());
            getInstrumentation().runOnMainSync(() -> {
                browser[0] = new MediaBrowserCompat(getInstrumentation().getTargetContext(), new ComponentName(getInstrumentation().getTargetContext(), PlayService.class), new MediaBrowserCompat.ConnectionCallback() {
                    @Override public void onConnected() { browser[0].subscribe(MediaCatalog.FAVORITES, new MediaBrowserCompat.SubscriptionCallback() {
                        @Override public void onChildrenLoaded(String parent, List<MediaBrowserCompat.MediaItem> items) { favorites.clear(); favorites.addAll(items); ready.countDown(); }
                    }); }
                }, null); browser[0].connect();
            });
            assertTrue(ready.await(8, TimeUnit.SECONDS));
            String firstId = favorites.stream().filter(item -> first.name.contentEquals(item.getDescription().getTitle())).findFirst().orElseThrow().getMediaId();
            String secondId = favorites.stream().filter(item -> second.name.contentEquals(item.getDescription().getTitle())).findFirst().orElseThrow().getMediaId();
            android.support.v4.media.session.MediaControllerCompat controller = new android.support.v4.media.session.MediaControllerCompat(getInstrumentation().getTargetContext(), browser[0].getSessionToken());
            controller.getTransportControls().playFromMediaId(firstId, null);
            detail = (com.kukuqi.tvbox.osc.ui.activity.DetailActivity)getInstrumentation().waitForMonitorWithTimeout(monitor, 8000); assertNotNull(detail);
            com.kukuqi.tvbox.osc.ui.activity.DetailActivity page = detail;
            awaitPlayback(page, first.id);
            controller.getTransportControls().playFromMediaId(secondId, null);
            awaitPlayback(page, second.id);
            assertFalse("Single-task detail was destroyed instead of receiving the new item", page.isDestroyed());
            assertEquals("Global preview preference was changed", false, (boolean)Hawk.get(HawkConfig.SHOW_PREVIEW, true));
        } finally {
            getInstrumentation().removeMonitor(monitor);
            com.kukuqi.tvbox.osc.ui.activity.DetailActivity page = detail;
            getInstrumentation().runOnMainSync(() -> { if (page != null) page.finish(); if (browser[0] != null) browser[0].disconnect(); });
            RoomDataManger.deleteVodCollect(link, first); RoomDataManger.deleteVodCollect(link, second);
            RoomDataManger.deleteVodRecord(link, first); RoomDataManger.deleteVodRecord(link, second);
            if (previous == null) sources.remove(link); else sources.put(link, previous);
            if (preview == null) Hawk.delete(HawkConfig.SHOW_PREVIEW); else Hawk.put(HawkConfig.SHOW_PREVIEW, preview);
            if (backend == null) Hawk.delete(HawkConfig.PLAY_TYPE); else Hawk.put(HawkConfig.PLAY_TYPE, backend);
            com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart = normal; server.stop();
        }
    }
    public void testLiveIntentSelectsDuplicateNamesByIndexAndHandlesNewIntent() throws Exception {
        File playlist = new File(getInstrumentation().getTargetContext().getCacheDir(), "media-browser-live-play.json");
        String group = "{\"group\":\"公开\",\"channels\":[{\"name\":\"同名台\",\"urls\":[\"file:///sdcard/TVboxOSC-regression/tvbox-regression.mp4\"]}]}";
        try (FileWriter writer = new FileWriter(playlist)) { writer.write("["+group+","+group+"]"); }
        boolean normal = com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart;
        com.kukuqi.tvbox.osc.ui.activity.LiveActivity activity = null;
        try {
            com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart = true;
            Hawk.put(HawkConfig.LIVE_URL, android.net.Uri.fromFile(playlist).toString());
            List<MediaBrowserCompat.MediaItem> groups = children(MediaCatalog.LIVE); assertEquals(2, groups.size());
            var first = children(groups.get(0).getMediaId()).get(0);
            var second = children(groups.get(1).getMediaId()).get(0);
            assertFalse(first.getMediaId().equals(second.getMediaId()));
            activity = (com.kukuqi.tvbox.osc.ui.activity.LiveActivity)getInstrumentation().startActivitySync(catalog.intent(second.getMediaId()));
            awaitLive(activity, 1);
            Intent target = catalog.intent(first.getMediaId());
            getInstrumentation().getTargetContext().startActivity(target);
            awaitLive(activity, 0);
        } finally {
            com.kukuqi.tvbox.osc.ui.activity.LiveActivity page = activity;
            getInstrumentation().runOnMainSync(() -> { if (page != null) page.finish(); });
            com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart = normal; playlist.delete();
        }
    }
    private void awaitLive(com.kukuqi.tvbox.osc.ui.activity.LiveActivity page, int index) throws Exception {
        Field video = page.getClass().getDeclaredField("mVideoView"); video.setAccessible(true);
        for (int i = 0; i < 150; i++) {
            boolean[] playing = {false};
            getInstrumentation().runOnMainSync(() -> {
                try { var player = (com.kukuqi.tvbox.osc.player.MyVideoView)video.get(page); playing[0] = page.currentChannelGroupIndex == index && player != null && player.isPlaying(); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
            if (playing[0]) return;
            Thread.sleep(100);
        }
        fail("Live browser did not select group "+index);
    }
    private void awaitPlayback(com.kukuqi.tvbox.osc.ui.activity.DetailActivity page, String url) throws Exception {
        Field field = page.getClass().getDeclaredField("playFragment"); field.setAccessible(true);
        for (int i = 0; i < 150; i++) {
            boolean[] playing = {false};
            getInstrumentation().runOnMainSync(() -> {
                try {
                    var play = (com.kukuqi.tvbox.osc.ui.fragment.PlayFragment)field.get(page);
                    playing[0] = play != null && url.equals(play.getFinalUrl()) && play.getPlayer() != null && play.getPlayer().isPlaying();
                } catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
            if (playing[0]) return;
            Thread.sleep(100);
        }
        fail("System browser did not play requested item: "+url);
    }
    private List<MediaBrowserCompat.MediaItem> children(String id) throws Exception {
        CountDownLatch done = new CountDownLatch(1); List<MediaBrowserCompat.MediaItem> items = Collections.synchronizedList(new ArrayList<>());
        getInstrumentation().runOnMainSync(() -> catalog.children(id, result -> { items.addAll(result); done.countDown(); }));
        assertTrue("Catalog timed out: "+id, done.await(10, TimeUnit.SECONDS)); return items;
    }
}
