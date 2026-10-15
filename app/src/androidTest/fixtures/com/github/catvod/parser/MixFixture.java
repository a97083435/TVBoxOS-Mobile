package com.github.catvod.parser;

import java.util.HashMap;
import java.util.LinkedHashMap;
import org.json.JSONObject;

public final class MixFixture {
    public static JSONObject parse(LinkedHashMap<String, HashMap<String, String>> parsers, String name, String flag, String url) throws Exception {
        return new JSONObject().put("parse", 0).put("url", url + "/video.mp4?mix=" + parsers.size() + "&flag=" + flag);
    }
}
