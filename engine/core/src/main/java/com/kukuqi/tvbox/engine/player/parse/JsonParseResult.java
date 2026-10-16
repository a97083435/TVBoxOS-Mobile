package com.kukuqi.tvbox.engine.player.parse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The JSON parser response protocol, shared by the host and core parsing paths. */
public final class JsonParseResult {
    public final String url;
    public final Map<String, String> headers;

    private JsonParseResult(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers;
    }

    public static JsonParseResult read(String json, Map<String, String> fallback, String endpoint) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : new JsonObject();
        String url = text(root.get("url"));
        if (url.isEmpty()) url = text(data.get("url"));
        if (url.startsWith("//")) url = (endpoint.startsWith("https:") ? "https:" : "http:") + url;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!java.util.Arrays.asList("http", "https", "rtmp", "rtmps", "rtsp", "proxy", "file", "content").contains(scheme)
                    || ((scheme.equals("http") || scheme.equals("https")) && (uri.getRawAuthority() == null || uri.getRawAuthority().isEmpty())))
                throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("解析没有返回有效播放地址", invalid);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (fallback != null) fallback.forEach((key, value) -> put(headers, key, value));
        addHeaders(headers, data);
        addHeaders(headers, root);
        return new JsonParseResult(url, headers);
    }

    private static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static void addHeaders(Map<String, String> headers, JsonObject object) {
        JsonElement header = object.get("header");
        try {
            if (header != null && header.isJsonPrimitive()) header = JsonParser.parseString(header.getAsString());
            if (header != null && header.isJsonObject()) for (Map.Entry<String, JsonElement> entry : header.getAsJsonObject().entrySet()) {
                String value = text(entry.getValue());
                if (!value.isEmpty()) put(headers, entry.getKey(), value);
            }
        } catch (RuntimeException ignored) { }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (java.util.Arrays.asList("ua", "user-agent", "referer", "cookie", "origin").contains(key)) {
                String value = text(entry.getValue());
                if (!value.isEmpty()) put(headers, key, value);
            }
        }
    }

    private static void put(Map<String, String> headers, String key, String value) {
        if (key.equalsIgnoreCase("ua") || key.equalsIgnoreCase("user-agent")) key = "User-Agent";
        else if (key.equalsIgnoreCase("referer")) key = "Referer";
        else if (key.equalsIgnoreCase("cookie")) key = "Cookie";
        else if (key.equalsIgnoreCase("origin")) key = "Origin";
        final String name = key;
        headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        headers.put(key, value);
    }
}
