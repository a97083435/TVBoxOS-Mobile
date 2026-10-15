package com.kukuqi.tvbox.engine.player.effect;

import com.kukuqi.tvbox.engine.R;
import com.kukuqi.tvbox.engine.player.effect.audio.AudioEffectBands;

public interface PlayerEffect {

    PlayerEffect NONE = new PlayerEffect() {
    };

    default boolean supportsVideoEffect() {
        return false;
    }

    default int getVideoEffectError() {
        return R.string.core_error_video_effect_unsupported;
    }

    default boolean supportsVideoSharpness() {
        return true;
    }

    default void applyVideoEffect() {
    }

    default void previewVideoEffect(boolean original) {
    }

    default boolean supportsAudioEffect() {
        return false;
    }

    default AudioEffectBands getAudioEffectBands() {
        return AudioEffectBands.EMPTY;
    }

    default int getAudioEffectError() {
        return R.string.core_error_audio_effect_unsupported;
    }

    default void applyAudioEffect() {
    }

    default void previewAudioEffect(boolean original) {
    }

    default boolean supportsSkipSilence() {
        return false;
    }

    default void setSkipSilenceEnabled(boolean enabled) {
    }
}
