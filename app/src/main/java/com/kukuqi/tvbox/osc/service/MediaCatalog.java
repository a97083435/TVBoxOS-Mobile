package com.kukuqi.tvbox.osc.service;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import com.google.gson.*;
import com.kukuqi.tvbox.osc.api.ApiConfig;
import com.kukuqi.tvbox.osc.bean.VodInfo;
import com.kukuqi.tvbox.osc.cache.*;
import com.kukuqi.tvbox.osc.data.AppDataManager;
import com.kukuqi.tvbox.osc.ui.activity.DetailActivity;
import com.kukuqi.tvbox.osc.ui.activity.LiveActivity;
import com.kukuqi.tvbox.osc.util.*;
import com.orhanobut.hawk.Hawk;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Browser IDs contain identity only. Playback always resolves against the current local catalog. */
public final class MediaCatalog implements AutoCloseable {
    public static final String ROOT = "tvboxosc_root", HISTORY = "history", FAVORITES = "favorites", LIVE = "live";
    public static final String EXTRA_LIVE_SOURCE = "media_live_source", EXTRA_LIVE_GROUP = "media_live_group", EXTRA_LIVE_CHANNEL = "media_live_channel";
    public static final String EXTRA_LIVE_GROUP_INDEX = "media_live_group_index", EXTRA_LIVE_CHANNEL_INDEX = "media_live_channel_index";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    private final Map<String, Intent> playable = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Intent> entry) { return size() > 4096; }
    });
    private final Context context;
    private JsonArray liveGroups;
    private String liveSource = "";
    private long loadedAt;
    private volatile boolean closed;
    public MediaCatalog(Context context) { this.context = context; }

    public void children(String parent, Consumer<List<MediaBrowserCompat.MediaItem>> reply) {
        if (ROOT.equals(parent)) {
            reply.accept(List.of(folder(HISTORY, "播放历史"), folder(FAVORITES, "我的收藏"), folder(LIVE, "直播频道"))); return;
        }
        if (parent.equals(LIVE) || parent.startsWith("live-group:")) { loadLive(parent, reply); return; }
        try {
            worker.execute(() -> {
                List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
                try {
                    if (HISTORY.equals(parent) && !Hawk.get(HawkConfig.PRIVATE_BROWSING, false)) {
                        for (VodRecord row : AppDataManager.get().getVodRecordDao().getAll(100)) {
                            try {
                                VodInfo info = new Gson().fromJson(row.dataJson, VodInfo.class);
                                if (info != null) addVod(items, row.sourceKey, row.vodId, info.name, info.playNote, info.pic);
                            } catch (RuntimeException ignored) { /* Skip a damaged old record. */ }
                        }
                    } else if (FAVORITES.equals(parent)) {
                        for (VodCollect row : AppDataManager.get().getVodCollectDao().getAll()) {
                            addVod(items, row.sourceKey, row.vodId, row.name, "收藏", row.pic);
                            if (items.size() >= 100) break;
                        }
                    }
                } catch (RuntimeException failure) { android.util.Log.w("MediaCatalog", "Cannot read library", failure); }
                main.post(() -> reply.accept(closed ? Collections.emptyList() : items));
            });
        } catch (RejectedExecutionException full) { reply.accept(Collections.emptyList()); }
    }
    private void addVod(List<MediaBrowserCompat.MediaItem> items, String source, String id, String name, String subtitle, String picture) {
        if (source == null || id == null || name == null || ApiConfig.get().getSource(source) == null) return;
        // Some sources use the full playback URL as vod_id; keep it out of browser IDs.
        String mediaId = new Uri.Builder().scheme("tvbox").authority("vod").appendPath(MD5.string2MD5(source + "\u0000" + id)).build().toString();
        Intent intent = new Intent(context, DetailActivity.class).putExtra("sourceKey", source).putExtra("id", id).putExtra("media_autoplay", true);
        playable.put(mediaId, intent);
        items.add(item(mediaId, name, subtitle, picture, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
    }
    private void loadLive(String parent, Consumer<List<MediaBrowserCompat.MediaItem>> reply) {
        String selected = Hawk.get(HawkConfig.LIVE_URL, "");
        if (selected.isEmpty()) { reply.accept(Collections.emptyList()); return; }
        if (selected.equals(liveSource) && liveGroups != null && android.os.SystemClock.elapsedRealtime() - loadedAt < 60000) {
            reply.accept(liveChildren(parent, selected, liveGroups)); return;
        }
        LiveSourceLoader.load(selected, (groups, error) -> {
            if (closed || !selected.equals(Hawk.get(HawkConfig.LIVE_URL, "")) || groups == null) { reply.accept(Collections.emptyList()); return; }
            liveSource = selected; liveGroups = groups.deepCopy(); loadedAt = android.os.SystemClock.elapsedRealtime();
            reply.accept(liveChildren(parent, selected, liveGroups));
        });
    }
    private List<MediaBrowserCompat.MediaItem> liveChildren(String parent, String source, JsonArray groups) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        String identity = MD5.string2MD5(source);
        for (int g = 0; g < groups.size(); g++) {
            JsonObject group = groups.get(g).getAsJsonObject();
            String name = SourceDescriptor.string(group, "group").trim();
            // Password groups stay private, even if the player unlocked one previously.
            if (name.contains("_") && !name.substring(name.indexOf('_') + 1).isEmpty()) continue;
            if (name.contains("_")) name = name.substring(0, name.indexOf('_'));
            String groupId = "live-group:" + identity + ":" + g;
            if (parent.equals(LIVE)) { items.add(folder(groupId, name)); continue; }
            if (!parent.equals(groupId) || !group.has("channels")) continue;
            JsonArray channels = group.getAsJsonArray("channels");
            for (int c = 0; c < channels.size() && c < 500; c++) {
                JsonObject channel = channels.get(c).getAsJsonObject(); String title = SourceDescriptor.string(channel, "name").trim();
                if (title.isEmpty() || !channel.has("urls") || channel.getAsJsonArray("urls").size() == 0) continue;
                String mediaId = new Uri.Builder().scheme("tvbox").authority("live").appendPath(identity).appendPath(String.valueOf(g)).appendPath(name).appendPath(title).appendPath(String.valueOf(c)).build().toString();
                playable.put(mediaId, new Intent(context, LiveActivity.class).putExtra(EXTRA_LIVE_SOURCE, source).putExtra(EXTRA_LIVE_GROUP, name).putExtra(EXTRA_LIVE_CHANNEL, title).putExtra(EXTRA_LIVE_GROUP_INDEX, g).putExtra(EXTRA_LIVE_CHANNEL_INDEX, c));
                items.add(item(mediaId, title, name, SourceDescriptor.string(channel, "logo"), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            }
        }
        return items;
    }
    public Intent intent(String mediaId) {
        Intent intent = playable.get(mediaId);
        if (intent == null || closed) return null;
        if (intent.hasExtra(EXTRA_LIVE_SOURCE)) {
            if (!intent.getStringExtra(EXTRA_LIVE_SOURCE).equals(Hawk.get(HawkConfig.LIVE_URL, ""))) return null;
        } else {
            if (ApiConfig.get().getSource(intent.getStringExtra("sourceKey")) == null) return null;
            String source = intent.getStringExtra("sourceKey"), id = intent.getStringExtra("id");
            boolean favorite = AppDataManager.get().getVodCollectDao().getVodCollect(source, id) != null;
            if (Hawk.get(HawkConfig.PRIVATE_BROWSING, false) && !favorite) return null;
            if (!favorite && AppDataManager.get().getVodRecordDao().getVodRecord(source, id) == null) return null;
        }
        return new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }
    private static MediaBrowserCompat.MediaItem folder(String id, String title) { return item(id, title, "", "", MediaBrowserCompat.MediaItem.FLAG_BROWSABLE); }
    public void invalidateLive() {
        liveGroups = null;
        synchronized (playable) { playable.entrySet().removeIf(entry -> entry.getValue().hasExtra(EXTRA_LIVE_SOURCE)); }
    }
    private static MediaBrowserCompat.MediaItem item(String id, String title, String subtitle, String picture, int flags) {
        MediaDescriptionCompat.Builder description = new MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle);
        if (picture != null && (picture.startsWith("https://") || picture.startsWith("http://"))) description.setIconUri(Uri.parse(picture));
        return new MediaBrowserCompat.MediaItem(description.build(), flags);
    }
    @Override public void close() { closed = true; worker.shutdownNow(); playable.clear(); liveGroups = null; }
}
