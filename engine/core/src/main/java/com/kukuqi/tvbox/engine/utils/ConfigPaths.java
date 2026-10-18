package com.kukuqi.tvbox.engine.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Resolve configuration resources without modifying script bodies, tokens or regexes. */
public final class ConfigPaths {
    private static final Set<String> PATHS = Set.of("spider", "jar", "api", "ext", "url",
            "sourceUrl", "playUrl", "wallpaper", "epg", "logo");
    private ConfigPaths() {}

    public static String resolve(String base, String path) {
        String prefix = path.startsWith("nodejs:") ? "nodejs:" : path.startsWith("node:") ? "node:" : "";
        String value = path.substring(prefix.length());
        if (!(value.startsWith("./") || value.startsWith("../") || value.startsWith("/"))) return path;
        // MD5 markers may contain another URL; resolve the resource independently.
        String[] parts = value.split(";md5;", 2);
        try {
            String resource = parts[0].replace("{", "%7B").replace("}", "%7D");
            okhttp3.HttpUrl http = okhttp3.HttpUrl.parse(base);
            okhttp3.HttpUrl resolved = http == null ? null : http.resolve(resource);
            String result = (resolved == null ? new URI(base).resolve(resource).toString() : resolved.toString())
                    .replace("%7B", "{").replace("%7D", "}");
            return prefix + result + (parts.length > 1 ? ";md5;" + resolve(base, parts[1]) : "");
        } catch (Exception ignored) { return path; }
    }

    public static void normalize(JsonElement element, String base) {
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) normalize(item, base);
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                if ((key.equals("urls") || key.equals("wallpaper")) && value.isJsonArray()) {
                    var paths = value.getAsJsonArray();
                    for (int i = 0; i < paths.size(); i++) if (isString(paths.get(i)))
                        paths.set(i, new JsonPrimitive(resolve(base, paths.get(i).getAsString())));
                }
                if (isString(value) && PATHS.contains(key))
                    entry.setValue(new JsonPrimitive(resolve(base, value.getAsString().trim())));
                else if (!key.equals("header")) normalize(value, base);
            }
        }
    }

    private static boolean isString(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
}
