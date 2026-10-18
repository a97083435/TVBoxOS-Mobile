package com.kukuqi.tvbox.engine.bean;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Invalid items are ignored individually, without discarding the playback response. */
public record SkipSegment(String type, long start, Long end) {
    public static SkipSegment from(JsonElement element) {
        try {
            JsonObject item = element.getAsJsonObject();
            String type = item.get("type").getAsString();
            if (!type.equals("opening") && !type.equals("middle") && !type.equals("ending")) return null;
            long start = item.get("start").getAsBigDecimal().longValueExact();
            Long end = !item.has("end") || item.get("end").isJsonNull() ? null : item.get("end").getAsBigDecimal().longValueExact();
            if (start < 0 || (end != null && end <= start) || (end == null && (!type.equals("ending") || start == 0))) return null;
            return new SkipSegment(type, start, end);
        } catch (RuntimeException ignored) { return null; }
    }
}
