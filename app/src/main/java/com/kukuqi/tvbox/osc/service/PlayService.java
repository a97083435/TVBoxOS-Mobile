package com.kukuqi.tvbox.osc.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import com.kukuqi.tvbox.osc.R;
import com.kukuqi.tvbox.osc.base.App;
import com.kukuqi.tvbox.osc.constant.IntentKey;
import com.kukuqi.tvbox.osc.event.RefreshEvent;
import com.kukuqi.tvbox.osc.event.SourceChangedEvent;
import com.kukuqi.tvbox.osc.data.AppDataManager;
import com.kukuqi.tvbox.osc.player.MyVideoView;
import com.kukuqi.tvbox.osc.ui.activity.DetailActivity;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;
import java.util.Set;

/** Exposes TVboxOSC playback to Android media controls while preserving the existing UI lifecycle. */
public class PlayService extends MediaBrowserServiceCompat {

    private static String videoInfo = "TVboxOSC&&正在播放";
    private static MyVideoView videoView;
    private static Intent resumeIntent;
    private static PlayService instance;

    private static final String CHANNEL_ID = "tvbox_playback";
    private static final int NOTIFICATION_ID = 1;
    private static final String BROWSER_ROOT = "tvboxosc_root";

    private MediaSessionCompat mediaSession;
    private MediaCatalog catalog;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshLibrary = () -> {
        if (instance != this) return;
        notifyChildrenChanged(MediaCatalog.HISTORY);
        notifyChildrenChanged(MediaCatalog.FAVORITES);
    };
    private final androidx.room.InvalidationTracker.Observer libraryObserver =
            new androidx.room.InvalidationTracker.Observer("vodRecord", "vodCollect") {
                @Override public void onInvalidated(@NonNull Set<String> tables) {
                    main.removeCallbacks(refreshLibrary);
                    main.postDelayed(refreshLibrary, 250);
                }
            };

    public static void start(MyVideoView controller, String currentVideoInfo) {
        start(controller, currentVideoInfo, new Intent(App.getInstance(), DetailActivity.class));
    }

    public static void start(MyVideoView controller, String currentVideoInfo, Intent target) {
        videoView = controller;
        videoInfo = currentVideoInfo == null ? "TVboxOSC&&正在播放" : currentVideoInfo;
        resumeIntent = target;
        ContextCompat.startForegroundService(App.getInstance(), new Intent(App.getInstance(), PlayService.class));
        if (instance != null) instance.updateSession();
    }

    public static void stop() {
        videoView = null;
        resumeIntent = null;
        videoInfo = "TVboxOSC&&请选择播放内容";
        if (instance != null) {
            instance.stopForeground(true);
            instance.updateSession();
        }
        App.getInstance().stopService(new Intent(App.getInstance(), PlayService.class));
    }

    public static void stop(MyVideoView owner) {
        if (owner != null && owner == videoView) stop();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        catalog = new MediaCatalog(this);
        AppDataManager.get().getInvalidationTracker().addObserver(libraryObserver);
        EventBus.getDefault().register(this);
        createNotificationChannel();
        createMediaSession();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示正在播放的内容和控制按钮");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void createMediaSession() {
        mediaSession = new MediaSessionCompat(this, "TVboxOSCPlayback");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setSessionActivity(getPendingIntentActivity());
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { if (videoView != null) videoView.start(); updateSession(); }
            @Override public void onPause() { if (videoView != null) videoView.pause(); updateSession(); }
            @Override public void onStop() { sendControl(IntentKey.BROADCAST_ACTION_CLOSE); }
            @Override public void onSkipToPrevious() { sendControl(IntentKey.BROADCAST_ACTION_PREV); }
            @Override public void onSkipToNext() { sendControl(IntentKey.BROADCAST_ACTION_NEXT); }
            @Override public void onSeekTo(long pos) { if (videoView != null) videoView.seekTo(Math.max(0, pos)); updateSession(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                Intent target = catalog.intent(mediaId);
                if (target != null) startActivity(target);
            }
        });
        mediaSession.setActive(true);
        setSessionToken(mediaSession.getSessionToken());
        updateSession();
    }

    private void sendControl(int action) {
        sendBroadcast(new Intent(IntentKey.BROADCAST_ACTION).putExtra("action", action).setPackage(getPackageName()));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mediaSession, intent);
        if (videoView == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        updateSession();
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_NOT_STICKY;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH || event.type == RefreshEvent.TYPE_REFRESH) {
            main.removeCallbacks(refreshLibrary);
            main.post(refreshLibrary);
        }
        if (event.type != RefreshEvent.TYPE_REFRESH_NOTIFY) return;
        if (event.obj != null) videoInfo = event.obj.toString();
        updateSession();
        if (videoView != null) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void sourceChanged(SourceChangedEvent event) {
        main.post(refreshLibrary);
        // Recording freshly fetched source metadata also emits this event.
        // Invalidating on that notification would make a live subscription reload itself.
        if (!event.selectionChanged) return;
        catalog.invalidateLive();
        notifyChildrenChanged(MediaCatalog.LIVE);
    }

    private void updateSession() {
        if (mediaSession == null) return;
        String[] parts = splitVideoInfo();
        long position = videoView == null ? 0 : Math.max(0, videoView.getCurrentPosition());
        long duration = videoView == null ? 0 : Math.max(0, videoView.getDuration());
        int state = videoView == null ? PlaybackStateCompat.STATE_NONE : videoView.isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO |
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().setActions(actions)
                .setState(state, position, state == PlaybackStateCompat.STATE_PLAYING ? 1f : 0f).build());
        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, parts[0])
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, parts[0])
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, parts[1]);
        if (duration > 0) metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        mediaSession.setMetadata(metadata.build());
        mediaSession.setSessionActivity(getPendingIntentActivity());
    }

    private String[] splitVideoInfo() {
        String[] parts = videoInfo.split("&&", 2);
        return new String[]{parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "TVboxOSC", parts.length > 1 ? parts[1] : "正在播放"};
    }

    private Notification buildNotification() {
        String[] parts = splitVideoInfo();
        RemoteViews big = new RemoteViews(getPackageName(), R.layout.notification_player);
        big.setTextViewText(R.id.tv_title, parts[0]);
        big.setTextViewText(R.id.tv_subtitle, "正在播放: " + parts[1]);
        big.setImageViewResource(R.id.iv_play_pause, videoView != null && videoView.isPlaying() ? R.drawable.ic_notify_pause : R.drawable.ic_notify_play);
        big.setOnClickPendingIntent(R.id.iv_previous, getPendingIntent(IntentKey.BROADCAST_ACTION_PREV));
        big.setOnClickPendingIntent(R.id.iv_play_pause, getPendingIntent(IntentKey.BROADCAST_ACTION_PLAYPAUSE));
        big.setOnClickPendingIntent(R.id.iv_next, getPendingIntent(IntentKey.BROADCAST_ACTION_NEXT));
        big.setOnClickPendingIntent(R.id.iv_close, getPendingIntent(IntentKey.BROADCAST_ACTION_CLOSE));

        RemoteViews small = new RemoteViews(getPackageName(), R.layout.notification_player_small);
        small.setTextViewText(R.id.tv_title, parts[0]);
        small.setTextViewText(R.id.tv_subtitle, "正在播放: " + parts[1]);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContent(small)
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setContentIntent(getPendingIntentActivity())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(videoView != null && videoView.isPlaying())
                .setStyle(new MediaStyle().setMediaSession(mediaSession.getSessionToken()))
                .build();
    }

    private PendingIntent getPendingIntentActivity() {
        Intent intent = resumeIntent == null ? new Intent(this, DetailActivity.class) : resumeIntent;
        return PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static PendingIntent getPendingIntent(int actionCode) {
        return PendingIntent.getBroadcast(App.getInstance(), actionCode,
                new Intent(IntentKey.BROADCAST_ACTION).putExtra("action", actionCode).setPackage(App.getInstance().getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        if (clientUid != android.os.Process.myUid() && !androidx.media.MediaSessionManager.getSessionManager(this)
                .isTrustedForMediaControl(new androidx.media.MediaSessionManager.RemoteUserInfo(clientPackageName, -1, clientUid))) return null;
        return new BrowserRoot(BROWSER_ROOT, null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.detach();
        catalog.children(parentId, result::sendResult);
    }

    @Override
    public void onDestroy() {
        AppDataManager.get().getInvalidationTracker().removeObserver(libraryObserver);
        main.removeCallbacksAndMessages(null);
        if (catalog != null) catalog.close();
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this);
        stopForeground(true);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        instance = null;
        videoView = null;
        resumeIntent = null;
        super.onDestroy();
    }
}
