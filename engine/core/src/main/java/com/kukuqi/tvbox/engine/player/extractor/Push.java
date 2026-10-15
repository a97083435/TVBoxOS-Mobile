package com.kukuqi.tvbox.engine.player.extractor;

import android.net.Uri;

import com.kukuqi.tvbox.engine.utils.UrlUtil;

public class Push implements Source.Extractor {

    @Override
    public boolean match(Uri uri) {
        return "push".equals(UrlUtil.scheme(uri));
    }

    @Override
    public String fetch(String url) throws Exception {
        return url.substring(7);
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
    }
}
