package com.kukuqi.tvbox.osc.util;

import com.kukuqi.tvbox.engine.player.parse.JsonParseResult;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class JsonParseProtocolTest {
    @Test public void shortUrlAndNullDataAreValid() {
        assertEquals("https://a.co/v", JsonParseResult.read("{\"url\":\"https://a.co/v\",\"data\":null}", null, "").url);
    }
    @Test public void schemeRelativeUrlUsesHttpsParserScheme() {
        assertEquals("https://cdn.example/v.m3u8", JsonParseResult.read("{\"data\":{\"url\":\"//cdn.example/v.m3u8\"}}", null, "https://parse.example/?url=").url);
    }
    @Test public void partialResponseHeadersPreserveInheritedAuthentication() {
        JsonParseResult result = JsonParseResult.read("{\"data\":{\"url\":\"https://a.co/v\",\"header\":\"{\\\"referer\\\":\\\"https://cms.example/\\\"}\"},\"UA\":\"ResponseAgent\"}", Collections.singletonMap("Cookie", "auth=ok"), "");
        assertEquals("auth=ok", result.headers.get("Cookie"));
        assertEquals("https://cms.example/", result.headers.get("Referer"));
        assertEquals("ResponseAgent", result.headers.get("User-Agent"));
    }
    @Test(expected = IllegalArgumentException.class) public void errorTextIsNotAMediaUrl() {
        JsonParseResult.read("{\"url\":\"The parser service is currently unavailable, please try again later.\"}", null, "");
    }
    @Test(expected = IllegalArgumentException.class) public void executableUrlIsNotAMediaUrl() {
        JsonParseResult.read("{\"url\":\"javascript:alert(1)\"}", null, "");
    }
}
