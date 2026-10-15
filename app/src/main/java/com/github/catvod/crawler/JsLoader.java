package com.github.catvod.crawler;
import com.kukuqi.tvbox.engine.api.loader.BaseLoader;
import java.util.Map;
public final class JsLoader {
    public static void load() { BaseLoader.get().clear(); }
    public static void stopAll() { com.github.catvod.net.OkHttp.cancelAll(); }
    public Spider getSpider(String key, String api, String ext, String jar) {
        BaseLoader.get().setRecent(key,api,jar);
        return BaseLoader.get().getSpider(key,api,ext,jar);
    }
    public Object[] proxyInvoke(Map<String,String> params) { try { return BaseLoader.get().proxy(params); } catch(Exception e) { return null; } }
}
