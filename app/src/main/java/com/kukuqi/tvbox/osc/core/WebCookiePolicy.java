package com.kukuqi.tvbox.osc.core;

import com.google.gson.*;
import java.net.URI;
import java.util.*;
import java.util.function.Function;

/** Closing is conditional on a changed, complete cookie group on its own origin. */
public final class WebCookiePolicy {
    private record Group(String url, List<String> keys, Map<String, String> before) {}
    private final List<Group> groups = new ArrayList<>();
    public WebCookiePolicy(JsonObject web, Function<String, String> cookies) {
        if (!web.has("cookie") || !web.get("cookie").isJsonArray()) return;
        for (JsonElement element : web.getAsJsonArray("cookie")) try {
            JsonObject item = element.getAsJsonObject();
            String url = item.has("url") ? item.get("url").getAsString() : web.get("url").getAsString();
            if (!http(url)) continue;
            List<String> keys = new ArrayList<>();
            for (JsonElement key : item.getAsJsonArray("keys")) if (!key.getAsString().isEmpty()) keys.add(key.getAsString());
            if (!keys.isEmpty()) groups.add(new Group(url, keys, parse(cookies.apply(url))));
        } catch (RuntimeException ignored) {}
    }
    public boolean complete(String page, Function<String, String> cookies) {
        for (Group group : groups) {
            if (!sameOrigin(page, group.url)) continue;
            Map<String, String> current = parse(cookies.apply(group.url)); boolean all = true, changed = false;
            for (String key : group.keys) {
                String value = current.get(key); all &= value != null && !value.isEmpty();
                changed |= value != null && !value.equals(group.before.get(key));
            }
            if (all && changed) return true;
        }
        return false;
    }
    public String snapshot() {
        JsonObject value = new JsonObject();
        for (Group group : groups) value.add(group.url, new Gson().toJsonTree(group.before));
        return value.toString();
    }
    public static WebCookiePolicy restore(JsonObject web, String snapshot, Function<String, String> cookies) {
        if (snapshot == null) return new WebCookiePolicy(web, cookies);
        try {
            JsonObject before = JsonParser.parseString(snapshot).getAsJsonObject();
            return new WebCookiePolicy(web, url -> {
                JsonObject values = before.has(url) ? before.getAsJsonObject(url) : new JsonObject();
                List<String> parts = new ArrayList<>();
                for (var item : values.entrySet()) parts.add(item.getKey() + "=" + item.getValue().getAsString());
                return String.join("; ", parts);
            });
        } catch (RuntimeException error) { return new WebCookiePolicy(web, cookies); }
    }
    public static boolean http(String url) {
        try { URI value = URI.create(url); return ("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme())) && value.getHost() != null; }
        catch (RuntimeException ignored) { return false; }
    }
    private static boolean sameOrigin(String a, String b) {
        if (!http(a) || !http(b)) return false;
        URI first = URI.create(a), second = URI.create(b);
        return first.getScheme().equalsIgnoreCase(second.getScheme()) && first.getHost().equalsIgnoreCase(second.getHost()) && port(first) == port(second);
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80; }
    private static Map<String, String> parse(String text) {
        Map<String, String> result = new HashMap<>();
        if (text != null) for (String part : text.split(";")) { int split = part.indexOf('='); if (split > 0) result.put(part.substring(0, split).trim(), part.substring(split + 1).trim()); }
        return result;
    }
}
