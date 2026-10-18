package com.kukuqi.tvbox.engine.api;

import android.util.Base64;

import com.kukuqi.tvbox.engine.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Response;

public class Decoder {

    public static String getJson(String url, String tag) throws Exception {
        try (Response res = OkHttp.newCall(url, tag).execute()) {
            HttpUrl httpUrl = res.request().url();
            int size = HttpUrl.parse(url).querySize();
            if (httpUrl.querySize() == size) url = httpUrl.toString();
            return verify(url, res.body().string());
        }
    }

    private static String verify(String url, String data) throws Exception {
        if (data.isEmpty()) throw new Exception();
        if (Json.isObj(data)) return fix(url, data);
        if (data.contains("**")) data = base64(data);
        if (data.startsWith("2423")) data = cbc(data.replaceAll("\\s+", ""));
        return fix(url, data);
    }

    public static String decode(String url, String data) throws Exception {
        return verify(url, data);
    }

    private static String fix(String url, String data) {
        try {
            com.google.gson.JsonElement document = com.google.gson.JsonParser.parseString(data);
            com.kukuqi.tvbox.engine.utils.ConfigPaths.normalize(document, url);
            return document.toString();
        } catch (RuntimeException ignored) { return data; }
    }

    private static String cbc(String data) throws Exception {
        String decode = new String(Util.hex2byte(data), StandardCharsets.UTF_8).toLowerCase();
        String key = padEnd(decode.substring(decode.indexOf("$#") + 2, decode.indexOf("#$")));
        String iv = padEnd(decode.substring(decode.length() - 13));
        data = data.substring(data.indexOf("2324") + 4, data.length() - 26);
        byte[] decrypted = Crypto.decryptAesCbc(Util.hex2byte(data), key.getBytes(StandardCharsets.UTF_8), iv.getBytes(StandardCharsets.UTF_8));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private static String base64(String data) {
        String extract = extract(data);
        if (extract.isEmpty()) return data;
        return new String(Base64.decode(extract, Base64.DEFAULT));
    }

    private static String extract(String data) {
        Matcher matcher = Pattern.compile("[A-Za-z0-9]{8}\\*\\*").matcher(data);
        return matcher.find() ? data.substring(data.indexOf(matcher.group()) + 10) : "";
    }

    private static String padEnd(String key) {
        return key + "0000000000000000".substring(key.length());
    }
}
