package com.github.catvod.parser;

import java.util.LinkedHashMap;
import org.json.JSONObject;

public final class JsonFixture {
    public static JSONObject parse(LinkedHashMap<String, String> parsers, String url) throws Exception {
        return new JSONObject().put("parse", 0).put("url", url + "/video.mp4?json=" + parsers.size());
    }
}
