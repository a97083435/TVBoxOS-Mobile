package com.kukuqi.tvbox.engine.playback.vod;

import android.text.TextUtils;

import com.kukuqi.tvbox.engine.bean.Result;

public record VodDetailResult(String key, String id, Result result) {

    public boolean matches(String key, String id) {
        return TextUtils.equals(this.key, key) && TextUtils.equals(this.id, id);
    }
}
