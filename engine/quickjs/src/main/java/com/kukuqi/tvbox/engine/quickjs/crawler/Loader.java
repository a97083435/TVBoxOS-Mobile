package com.kukuqi.tvbox.engine.quickjs.crawler;

import com.whl.quickjs.android.QuickJSLoader;

import dalvik.system.DexClassLoader;

public class Loader {

    public Loader(android.content.Context context) {
        com.kukuqi.tvbox.engine.quickjs.utils.RuntimeAssets.init(context);
        QuickJSLoader.init();
    }

    public void clear() { com.kukuqi.tvbox.engine.quickjs.utils.Module.get().clear(); }

    public Spider spider(String api, DexClassLoader dex) {
        return new Spider(api, dex);
    }
}
