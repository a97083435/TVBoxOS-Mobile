package com.kukuqi.tvbox.engine.playback;

import com.kukuqi.tvbox.engine.bean.Result;

public record PlaybackResult<T>(T request, Result result) {
}
