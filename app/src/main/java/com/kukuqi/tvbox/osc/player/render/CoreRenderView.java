package com.kukuqi.tvbox.osc.player.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.media3.ui.SubtitleView;
import com.kukuqi.tvbox.osc.player.CorePlayer;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.render.IRenderView;
import xyz.doikki.videoplayer.render.TextureRenderView;

/** Keeps OSC's surfaces and scaling, while rendering full Media3 text/bitmap cues. */
public final class CoreRenderView extends FrameLayout implements IRenderView {
    private final IRenderView surface;
    private final SubtitleView subtitles;
    private final SubtitleView secondarySubtitles;
    private CorePlayer player;
    public CoreRenderView(Context context, int render) {
        super(context);
        surface = render == 1 ? new SurfaceRenderView(context) : new TextureRenderView(context);
        addView(surface.getView(),new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT,Gravity.CENTER));
        subtitles = new SubtitleView(context);
        subtitles.setVisibility(GONE);
        com.kukuqi.tvbox.engine.setting.SubtitleSetting.applyStyle(subtitles);
        addView(subtitles,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        secondarySubtitles = new SubtitleView(context);
        secondarySubtitles.setVisibility(GONE);
        addView(secondarySubtitles, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }
    @Override public void attachToPlayer(@NonNull AbstractPlayer value) {
        surface.attachToPlayer(value);
        if (!(value instanceof CorePlayer)) return;
        player=(CorePlayer)value;
        player.setSubtitleStyleListener(() -> {
            subtitles.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, player.getSubtitleTextSize());
            subtitles.setStyle(new androidx.media3.ui.CaptionStyleCompat(player.getSubtitleTextColor(), android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null));
        });
        player.setRenderedCueListener(group -> {
            boolean show=player != null && player.useRenderedSubtitles();
            subtitles.setVisibility(show ? VISIBLE : GONE);
            subtitles.setCues(show ? group.cues : java.util.Collections.emptyList());
            secondarySubtitles.setVisibility(show ? VISIBLE : GONE);
        });
        player.manager().setSecondarySubtitleOutput(group -> {
            com.kukuqi.tvbox.engine.setting.SubtitleSetting.applyStyle(secondarySubtitles);
            secondarySubtitles.setBottomPosition(0);
            secondarySubtitles.setBottomPaddingFraction(Math.max(0, (100f - com.kukuqi.tvbox.engine.setting.SubtitleSetting.getSecondaryPosition()) / 100f));
            secondarySubtitles.setVisibility(player != null && player.useRenderedSubtitles() ? VISIBLE : GONE);
            secondarySubtitles.setCues(group.cues);
        });
    }
    @Override public void setVideoSize(int width,int height) {surface.setVideoSize(width,height);}
    @Override public void setVideoRotation(int degree) {surface.setVideoRotation(degree);}
    @Override public void setScaleType(int type) {surface.setScaleType(type);}
    @Override public View getView() {return this;}
    @Override public Bitmap doScreenShot() {return surface.doScreenShot();}
    @Override public void release() {
        if(player != null) { player.setRenderedCueListener(null); player.setSubtitleStyleListener(null);
            if (player.manager() != null && !player.manager().isReleased()) player.manager().setSecondarySubtitleOutput(null);
        }
        secondarySubtitles.setCues(java.util.List.of());
        player=null; subtitles.setCues(java.util.Collections.emptyList()); surface.release();
    }
}
