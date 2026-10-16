package com.kukuqi.tvbox.osc.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.orhanobut.hawk.Hawk;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Public trending titles, cached independently of private search history. */
public final class HotSearch {
    public interface Result { void ready(List<String> words, String status); }
    private static final String URL = "https://api.web.360kan.com/v1/rank?cat=1";
    private HotSearch() {}
    public static List<String> parse(String content) {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        JsonElement root = JsonParser.parseString(content);
        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("data")) {
                root = object.get("data");
                if (root.isJsonObject()) root = root.getAsJsonObject().getAsJsonObject("mapResult").getAsJsonObject("0").get("listInfo");
            }
        }
        if (root != null && root.isJsonArray()) for (JsonElement item : root.getAsJsonArray()) {
            String title = item.isJsonPrimitive() ? item.getAsString() : item.isJsonObject() ? SourceDescriptor.string(item.getAsJsonObject(), "title") : "";
            title = title.replaceAll("<[^>]*>", "").replace("《", "").replace("》", "").trim();
            if (!title.isEmpty() && title.length() <= 60) words.add(title);
            if (words.size() >= 20) break;
        }
        return new ArrayList<>(words);
    }
    public static void load(boolean force, Result result) {
        List<String> cache = cached();
        if (!cache.isEmpty()) result.ready(cache, "上次热门 · 点击刷新");
        if (!force && !cache.isEmpty() && System.currentTimeMillis() - Hawk.get("hot_search_time", 0L) < 6 * 60 * 60 * 1000L) return;
        fetch(cache, result);
    }
    private static List<String> cached() {
        try { return parse(Hawk.get("hot_search_cache", "[]")); } catch (Exception ignored) { return new ArrayList<>(); }
    }
    private static void fetch(List<String> cache, Result result) {
        SourceManager.read(URL, java.util.Collections.singletonMap("Referer", "https://www.360kan.com/rank/general"), (content, error) -> {
            List<String> words = new ArrayList<>();
            try { if (content != null) words = parse(content); } catch (Exception ignored) {}
            if (!words.isEmpty()) {
                Hawk.put("hot_search_cache", new com.google.gson.Gson().toJson(words)); Hawk.put("hot_search_time", System.currentTimeMillis());
                result.ready(words, "热门搜索 · 点击刷新");
            } else {
                result.ready(cache, cache.isEmpty() ? (error.contains("403") ? "热门榜单访问受限，可直接输入片名搜索 · 点击重试" : "热门词暂不可用，可直接输入片名搜索 · 点击重试") : "热门词刷新失败，展示上次热门 · 点击重试");
            }
        });
    }
}
