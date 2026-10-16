package com.kukuqi.tvbox.engine.api.loader;
import com.github.catvod.crawler.Spider;
import java.util.*;
import org.json.*;

final class NodeSpider extends Spider {
    private final NodeLoader loader;
    NodeSpider(NodeLoader loader, String key) { this.loader = loader; siteKey = key; }
    private String call(String method, Object... args) throws Exception {
        Object result = loader.rpc(siteKey, method, args); return result == null || result == JSONObject.NULL ? "" : result.toString();
    }
    @Override public String homeContent(boolean filter) throws Exception { return call("homeContent", filter); }
    @Override public String homeVideoContent() throws Exception { return call("homeVideoContent"); }
    @Override public String categoryContent(String tid, String pg, boolean filter, HashMap<String,String> extend) throws Exception { return call("categoryContent", tid, pg, filter, extend); }
    @Override public String detailContent(List<String> ids) throws Exception { return call("detailContent", ids); }
    @Override public String searchContent(String key, boolean quick) throws Exception { return call("searchContent", key, quick); }
    @Override public String searchContent(String key, boolean quick, String pg) throws Exception { return call("searchContent", key, quick, pg); }
    @Override public String playerContent(String flag, String id, List<String> flags) throws Exception { return call("playerContent", flag, id, flags); }
    @Override public String liveContent(String url) throws Exception { return call("liveContent", url); }
    @Override public String action(String action) throws Exception { return call("action", action); }
    @Override public boolean manualVideoCheck() throws Exception { return Boolean.parseBoolean(call("manualVideoCheck")); }
    @Override public boolean isVideoFormat(String url) throws Exception { return Boolean.parseBoolean(call("isVideoFormat", url)); }
    @Override public Object[] proxy(Map<String,String> params) throws Exception {
        Object value = loader.rpc(siteKey, "proxy", params);
        if (value instanceof JSONObject object && object.has("body")) return new Object[]{object.getInt("status"), object.getString("mime"), new java.io.ByteArrayInputStream(android.util.Base64.decode(object.getString("body"), android.util.Base64.DEFAULT)), headers(object.optJSONObject("headers"))};
        if (!(value instanceof JSONArray array) || array.length() < 3) return null;
        byte[] body = array.getString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new Object[]{array.getInt(0), array.getString(1), new java.io.ByteArrayInputStream(body), headers(array.optJSONObject(3))};
    }
    private static Map<String,String> headers(JSONObject object) throws Exception {
        Map<String,String> headers = new HashMap<>();
        if (object != null) for (Iterator<String> keys = object.keys(); keys.hasNext();) { String key = keys.next(); headers.put(key, object.getString(key)); }
        return headers;
    }
    @Override public void destroy() { try { call("destroy"); } catch (Exception ignored) {} }
}
