package com.kukuqi.tvbox.engine.player.mpv;

import androidx.annotation.NonNull;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.mpvplayer.MpvPlayer;

import com.kukuqi.tvbox.engine.bean.Sub;
import com.kukuqi.tvbox.engine.player.effect.PlayerEffect;
import com.kukuqi.tvbox.engine.player.engine.PlayerEngine;
import com.kukuqi.tvbox.engine.player.media.MediaItemFactory;
import com.kukuqi.tvbox.engine.player.media.PlaySpec;

public class MpvPlayerEngine implements PlayerEngine, Player.Listener {

    private final MpvErrorMessageProvider provider;
    private final MpvPlayerEffect effect;
    private final MpvPlayer player;
    private final Player.Listener listener;
    private PlaySpec spec;
    private int generation;
    private boolean released;
    private androidx.media3.common.TrackSelectionOverride secondarySelection;
    private static final java.util.concurrent.ExecutorService discWorker = java.util.concurrent.Executors.newSingleThreadExecutor();

    public MpvPlayerEngine(int decode, Player.Listener listener) {
        this.listener = listener;
        this.player = MpvUtil.buildPlayer(decode, listener);
        this.provider = new MpvErrorMessageProvider();
        this.effect = new MpvPlayerEffect(player);
        this.player.setAudioOutputListener(effect::applyAudioEffect);
        this.player.addListener(this);
    }

    public static boolean isAvailable() {
        return MpvUtil.isAvailable();
    }

    @Override
    public Type getType() {
        return Type.MPV;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public int getAudioChannelCount() {
        return player.getAudioChannelCount();
    }

    @Override
    public PlayerEffect getEffect() {
        return effect;
    }

    @Override
    public void release() {
        released = true; generation++;
        player.removeListener(this);
        player.setAudioOutputListener(null);
        player.release();
    }

    @Override
    public void applySubtitleStyle() {
        Integer id = null;
        if (secondarySelection != null) {
            try { id = Integer.parseInt(secondarySelection.mediaTrackGroup.getFormat(secondarySelection.trackIndices.get(0)).id); }
            catch (RuntimeException invalid) { secondarySelection = null; }
        }
        MpvUtil.setSubtitleStyle(player, id);
    }

    @Override public SecondarySubtitleState getSecondarySubtitleState() {
        androidx.media3.common.TrackSelectionOverride primary = null;
        java.util.List<androidx.media3.common.TrackSelectionOverride> candidates = new java.util.ArrayList<>();
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != androidx.media3.common.C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                var selection = new androidx.media3.common.TrackSelectionOverride(group.getMediaTrackGroup(), i);
                if (group.isTrackSelected(i)) primary = selection;
                else candidates.add(selection);
            }
        }
        var selected = candidates.contains(secondarySelection) ? secondarySelection : null;
        return new SecondarySubtitleState(primary, selected, candidates, false);
    }

    @Override public void setSecondarySubtitleSelection(androidx.media3.common.TrackSelectionOverride selection) {
        if (selection != null && !getSecondarySubtitleState().secondaryCandidates().contains(selection)) return;
        secondarySelection = selection;
        applySubtitleStyle();
    }

    @Override
    public boolean addSubtitle(Sub sub) {
        if (sub == null || sub.isEmpty() || player.getCurrentMediaItem() == null) return false;
        if (player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) return false;
        player.addSubtitle(MediaItemFactory.buildSubConfig(sub));
        return true;
    }

    @Override
    public void setDecode(int decode) {
        player.setDecode(decode);
    }

    @Override
    public void onTracksChanged(@NonNull Tracks tracks) {
        effect.applyVideoEffect();
        if (secondarySelection != null && getSecondarySubtitleState().explicitSelection() == null) {
            secondarySelection = null;
            applySubtitleStyle();
        }
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        secondarySelection = null;
        applySubtitleStyle();
        int request = ++generation;
        if (com.kukuqi.tvbox.engine.runtime.BdjSupport.isMenu(spec.getUrl())) {
            listener.onPlayerError(new PlaybackException("当前播放内核不支持交互式蓝光菜单，请选择主标题或章节播放", null,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED));
            return;
        }
        if (com.kukuqi.tvbox.engine.runtime.BdjSupport.shouldPrepare(spec.getUrl())) {
            discWorker.execute(() -> {
                try {
                    com.kukuqi.tvbox.engine.runtime.BdjSupport.prepare();
                    com.kukuqi.tvbox.engine.App.post(() -> { if (!released && generation == request) startInternal(startPositionMs); });
                } catch (Throwable failure) {
                    com.kukuqi.tvbox.engine.App.post(() -> {
                        if (!released && generation == request) {
                            android.widget.Toast.makeText(com.kukuqi.tvbox.engine.App.get(), "BD-J 加载失败：" + failure.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                            // Main-title playback remains possible without Java menus.
                            startInternal(startPositionMs);
                        }
                    });
                }
            });
        } else startInternal(startPositionMs);
    }

    private void startInternal(long startPositionMs) {
        effect.applyVideoEffect();
        effect.clearAudioEffect();
        player.setMediaItem(MediaItemFactory.from(spec), startPositionMs);
        prepareAndPlay();
    }

    private void prepareAndPlay() {
        player.prepare();
        player.play();
    }

    @Override
    public void stop() {
        generation++;
        player.stop();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        if (spec != null && com.kukuqi.tvbox.engine.runtime.BdjSupport.isMenu(spec.getUrl()))
            return "当前播放内核不支持交互式蓝光菜单，请选择主标题或章节播放";
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> retryHls();
            default -> ErrorAction.FATAL;
        };
    }

    private ErrorAction retryHls() {
        if (spec == null || MimeTypes.APPLICATION_M3U8.equals(spec.getFormat())) return ErrorAction.FATAL;
        spec.setFormat(MimeTypes.APPLICATION_M3U8);
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }
}
