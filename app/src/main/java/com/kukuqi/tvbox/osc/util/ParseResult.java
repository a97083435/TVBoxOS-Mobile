package com.kukuqi.tvbox.osc.util;

import java.util.Map;

/** JSON parser protocol shared by standalone and aggregate parsers. */
public final class ParseResult {
    public final String url;
    public final Map<String, String> headers;
    private ParseResult(String url, Map<String, String> headers) { this.url = url; this.headers = headers; }
    public static ParseResult read(String json, Map<String, String> fallback) {
        com.kukuqi.tvbox.engine.player.parse.JsonParseResult result = com.kukuqi.tvbox.engine.player.parse.JsonParseResult.read(json, fallback, "");
        return new ParseResult(result.url, result.headers);
    }
}
