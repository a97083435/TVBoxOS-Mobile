package com.kukuqi.tvbox.osc.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.kukuqi.tvbox.osc.core.LiveCoreBridge;

public final class LiveSourceLoader {
    public interface Result { void onResult(JsonArray groups, String error); }
    public static void load(String url, Result result) {
        SourceManager.read(url, (content, error) -> {
            if (content == null) { result.onResult(null,error); return; }
            try {
                String text = com.kukuqi.tvbox.osc.api.ApiConfig.FindResult(content,null);
                if (text.trim().startsWith("{") && JsonParser.parseString(text).getAsJsonObject().has("urls")) {
                    SourceManager.readConfig(url,(resolved,failure) -> {
                        if (resolved == null) result.onResult(null,failure);
                        else parse(url,resolved,result);
                    });
                } else parse(url,text,result);
            } catch (Exception failure) { result.onResult(null,"直播内容无法解析，请检查来源格式"); }
        });
    }
    private static void parse(String url,String content,Result result) {
        HeavyTaskUtil.executeNewTask(() -> {
            JsonArray ready = null; String error = "";
            try { ready = LiveCoreBridge.parse(url,content); SourceManager.record(url,content); }
            catch (Exception failure) { error = failure.getMessage(); }
            JsonArray groups = ready; String message = error;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> result.onResult(groups,message));
        });
    }
    public static String epg(JsonArray groups) {
        return groups == null || groups.size() == 0 ? "" : SourceDescriptor.string(groups.get(0).getAsJsonObject(),"epg");
    }
}
