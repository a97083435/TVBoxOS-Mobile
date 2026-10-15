package com.github.catvod.crawler;
import com.kukuqi.tvbox.engine.api.loader.BaseLoader;
import java.util.*;
import org.json.JSONObject;
public final class JarLoader {
    private String main = "";
    public void clear() { BaseLoader.get().clear(); }
    public boolean load(String path) {
        if (!new java.io.File(path).setReadOnly()) return false;
        main = "file://" + path;
        BaseLoader.get().parseJar(main, true);
        return BaseLoader.get().dex(main) != null;
    }
    public Spider getSpider(String key, String api, String ext, String jar) {
        String selected = jar == null || jar.isEmpty() ? main : jar;
        BaseLoader.get().setRecent(key, api, selected);
        return BaseLoader.get().getSpider(key, api, ext, selected);
    }
    public Object[] proxyInvoke(Map params) { try { return BaseLoader.get().proxy(params); } catch(Exception e) { return null; } }
    public JSONObject jsonExt(String key, LinkedHashMap<String,String> lines, String url) { try { return BaseLoader.get().jsonExt(key, lines, url); } catch(Throwable e) { return null; } }
    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String,HashMap<String,String>> lines, String url) { try { return BaseLoader.get().jsonExtMix(flag,key,name,lines,url); } catch(Throwable e) { return null; } }
}
