package com.kukuqi.tvbox.engine.playback.vod;

import androidx.media3.common.MediaMetadata;

import com.kukuqi.tvbox.engine.api.DanmakuApi;
import com.kukuqi.tvbox.engine.api.config.VodConfig;
import com.kukuqi.tvbox.engine.bean.Danmaku;
import com.kukuqi.tvbox.engine.bean.Episode;
import com.kukuqi.tvbox.engine.bean.History;
import com.kukuqi.tvbox.engine.bean.Result;
import com.kukuqi.tvbox.engine.player.media.MediaItemFactory;
import com.kukuqi.tvbox.engine.setting.DanmakuSetting;

import java.util.function.Consumer;

public final class VodPlaybackMedia {

    public static MediaMetadata metadata(History history, Episode episode) {
        String title = history.getVodName();
        String name = episode.getName();
        if (name.equals(title)) name = "";
        return MediaItemFactory.buildMetadata(title, name, history.getVodPic(), name);
    }

    public static void searchDanmaku(Result result, History history, Episode episode, Consumer<Danmaku> set, Consumer<Danmaku> add) {
        if (!DanmakuApi.canSearch()) return;
        if (VodConfig.get().getSite(result.getKey()).getDanmaku() == 0) return;
        DanmakuApi.search(history.getVodName(), episode.getName(), danmaku -> {
            if (DanmakuSetting.isSpiderFirst() && !result.getDanmaku().isEmpty()) add.accept(danmaku);
            else set.accept(danmaku);
        });
    }
}
