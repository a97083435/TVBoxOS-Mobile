package com.kukuqi.tvbox.osc.core;

import com.google.gson.*;
import com.kukuqi.tvbox.engine.bean.SkipSegment;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SourceResponsePolicyTest {
    @Test public void sourceCardsKeepActionsFoldersAndStringCategoryIds() {
        var document = new Gson().fromJson("{\"list\":[{\"vod_id\":\"login\",\"type_id\":\"account\",\"vod_name\":\"Login\",\"action\":\"signin\",\"cate\":{\"land\":1},\"circle\":1},{\"vod_id\":\"movie\",\"type_id\":32}]}", com.kukuqi.tvbox.osc.bean.AbsJson.class);
        var result = document.toAbsXml().movie.videoList;
        assertEquals(2, result.size()); assertEquals("folder", result.get(0).tag); assertEquals("signin", result.get(0).action);
        assertTrue(result.get(0).cate.contains("land")); assertTrue(result.get(0).cardStyle.contains("oval")); assertEquals(32, result.get(1).tid);
    }
    @Test public void loginRequiresAllCookiesChangeAndExactOriginAcrossRecreation() {
        JsonObject web = JsonParser.parseString("{\"url\":\"https://example.com/login\",\"cookie\":[{\"keys\":[\"sid\",\"token\"]}]}").getAsJsonObject();
        WebCookiePolicy policy = new WebCookiePolicy(web, url -> "sid=old; token=old");
        assertFalse(policy.complete("https://example.com/", url -> "sid=old; token=old"));
        assertFalse(policy.complete("https://example.com/", url -> "sid=new"));
        assertFalse(policy.complete("https://example.com:444/", url -> "sid=new; token=old"));
        assertFalse(policy.complete("http://example.com/", url -> "sid=new; token=old"));
        assertFalse(policy.complete("https://other.example.com/", url -> "sid=new; token=old"));
        policy = WebCookiePolicy.restore(web, policy.snapshot(), url -> "sid=new; token=old");
        assertTrue(policy.complete("https://example.com:443/", url -> "sid=new; token=old"));
        assertFalse(WebCookiePolicy.http("javascript:alert(1)"));
    }
    @Test public void skipValidationAndManualSettingsPreserveMiddleSegments() {
        JsonArray input = JsonParser.parseString("[{\"type\":\"opening\",\"start\":0,\"end\":1000},{\"type\":\"middle\",\"start\":2000,\"end\":3000},{\"type\":\"ending\",\"start\":9000},{\"type\":\"middle\",\"start\":3},{\"type\":\"opening\",\"start\":0.1,\"end\":1}]").getAsJsonArray();
        List<SkipSegment> segments = new ArrayList<>(); for (JsonElement item : input) { SkipSegment value = SkipSegment.from(item); if (value != null) segments.add(value); }
        assertEquals(3, segments.size()); SkipPolicy policy = new SkipPolicy();
        assertNull(policy.progress(segments, 0, 10000, 500, 0, true));
        assertEquals(3000, policy.progress(segments, 2100, 10000, 500, 0, true).position());
        assertNull(policy.progress(segments, 2200, 10000, 0, 0, true));
        assertNull(policy.progress(segments, 9000, 10000, 0, 500, true));
        assertTrue(policy.progress(segments, 9000, 10000, 0, 0, true).next());
        policy.reset(); assertEquals(10000, policy.progress(segments, 9000, 10000, 0, 0, false).position());
    }
}
