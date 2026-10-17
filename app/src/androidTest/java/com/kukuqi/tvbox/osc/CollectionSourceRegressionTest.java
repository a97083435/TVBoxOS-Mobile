package com.kukuqi.tvbox.osc;

import android.test.InstrumentationTestCase;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kukuqi.tvbox.engine.api.SiteApi;
import com.kukuqi.tvbox.engine.api.config.VodConfig;
import com.kukuqi.tvbox.engine.bean.Parse;
import com.kukuqi.tvbox.engine.bean.Result;
import com.kukuqi.tvbox.engine.bean.Site;
import com.kukuqi.tvbox.engine.impl.ParseCallback;
import com.kukuqi.tvbox.engine.player.parse.ParseJob;
import com.kukuqi.tvbox.engine.utils.Sniffer;
import com.kukuqi.tvbox.osc.bean.AbsJson;
import com.kukuqi.tvbox.osc.bean.AbsXml;
import com.kukuqi.tvbox.osc.util.DefaultConfig;
import com.kukuqi.tvbox.osc.viewmodel.SourceViewModel;
import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** MacCMS V10 collection fixtures and the real core/host adapters, without public endpoints. */
public class CollectionSourceRegressionTest extends InstrumentationTestCase {
    private NanoHTTPD server;
    private String base;
    private volatile Map<String, String> lastQuery;
    private volatile Map<String, String> searchQuery;
    private volatile Map<String, String> parserHeaders;
    private volatile boolean failPictures;
    private volatile int actionCount;

    @Override protected void setUp() throws Exception {
        super.setUp();
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                String path = session.getUri();
                if (path.equals("/denied")) return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"url\":\"https://example.org/valid-looking-but-denied.mp4\"}");
                if (path.equals("/html")) return newFixedLengthResponse("<html>login required</html>");
                if (path.equals("/web")) {
                    parserHeaders = new HashMap<>(session.getHeaders());
                    lastQuery = new HashMap<>(session.getParms());
                    if (!"parser".equals(parserHeaders.get("x-parse")) || !"retained".equals(lastQuery.get("parser-token")))
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "missing parser credentials");
                    return newFixedLengthResponse("<html><video autoplay muted src='" + base + "/v.mp4?fixture=web'></video></html>");
                }
                if (path.startsWith("/parse")) {
                    parserHeaders = new HashMap<>(session.getHeaders());
                    JsonObject root = new JsonObject();
                    JsonObject data = new JsonObject();
                    data.addProperty("url", base + "/v.mp4");
                    data.add("header", JsonParser.parseString("{\"cookie\":\"session=ok\",\"referer\":\"https://cms.example/\"}"));
                    root.add("data", data);
                    root.addProperty("Origin", "https://cms.example");
                    return newFixedLengthResponse(Response.Status.OK, "application/json", root.toString());
                }
                lastQuery = new HashMap<>(session.getParms());
                actionCount = session.getParameters().getOrDefault("ac", Collections.emptyList()).size();
                if (lastQuery.containsKey("wd")) searchQuery = new HashMap<>(lastQuery);
                if (session.getParms().containsKey("ids") && failPictures)
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "temporarily unavailable");
                boolean xml = path.contains("xml");
                String response = xml ? xml(session.getParms()) : json(session.getParms());
                return newFixedLengthResponse(Response.Status.OK, xml ? "text/xml" : "application/json", response);
            }
        };
        server.start();
        base = "http://127.0.0.1:" + server.getListeningPort();
        configure();
    }

    private void configure() {
        JsonObject config = new JsonObject();
        JsonArray sites = new JsonArray();
        for (String key : new String[]{"json", "xml"}) {
            JsonObject site = new JsonObject();
            site.addProperty("key", key); site.addProperty("name", key);
            site.addProperty("type", key.equals("xml") ? 0 : 1);
            site.addProperty("api", base + "/" + key + "/api.php/provide/vod/?token=retained");
            site.add("header", JsonParser.parseString("{\"X-Site\":\"site\",\"User-Agent\":\"SourceAgent\"}"));
            sites.add(site);
        }
        config.add("sites", sites);
        config.add("flags", JsonParser.parseString("[\"web\"]"));
        config.add("parses", JsonParser.parseString("[{\"name\":\"fixture\",\"type\":1,\"url\":\"" + base + "/parse?url=\",\"ext\":{\"header\":{\"X-Parse\":\"parser\",\"User-Agent\":\"ParserAgent\"}}}]"));
        VodConfig.get().accept(base + "/config.json", "json", config);
    }

    private String json(Map<String, String> query) {
        JsonObject root = new JsonObject();
        root.addProperty("code", 1); root.addProperty("page", query.getOrDefault("pg", "1"));
        root.addProperty("pagecount", "3"); root.addProperty("limit", "2"); root.addProperty("total", 5);
        root.add("class", JsonParser.parseString("[{\"type_id\":7,\"type_name\":\"电视剧\"}]"));
        JsonArray list = new JsonArray();
        if (query.containsKey("ids")) {
            for (String id : new String[]{"2", "1"}) if (java.util.Arrays.asList(query.get("ids").split(",")).contains(id)) list.add(vod(id, true));
        } else {
            list.add(vod("1", true)); list.add(vod("2", false));
        }
        root.add("list", list);
        return root.toString();
    }

    private JsonObject vod(String id, boolean picture) {
        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", id); vod.addProperty("vod_name", "影片" + id);
        vod.addProperty("type_id", 7); vod.addProperty("type_name", "电视剧");
        vod.addProperty("vod_pic", picture ? base + "/" + id + ".jpg" : "");
        vod.addProperty("vod_play_from", "direct$$$web");
        vod.addProperty("vod_play_url", " 第1集$" + base + "/v.mp4?token=a$b#第2集$" + base + "/v.m3u8$$$第1集$https://cms.example/watch/1");
        return vod;
    }

    private String xml(Map<String, String> query) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><rss version=\"5.1\"><class><ty id=\"7\">电视剧</ty></class>"
                + "<list page=\"" + query.getOrDefault("pg", "1") + "\" pagecount=\"3\" pagesize=\"2\" recordcount=\"5\">"
                + "<video><id>1</id><tid>7</tid><name><![CDATA[XML影片]]></name><type>电视剧</type><pic>" + base + "/1.jpg</pic>"
                + "<note>更新至2集</note><dl><dd flag=\"direct\"><![CDATA[ 第1集$" + base + "/v.mp4?token=a$b#第2集$" + base + "/v.m3u8]]></dd>"
                + "<dd flag=\"web\"><![CDATA[第1集$https://cms.example/watch/1]]></dd></dl></video></list></rss>";
    }

    @Override protected void tearDown() throws Exception { server.stop(); super.tearDown(); }

    public void testJsonHomeCompletesOnlyMissingPicturesAndKeepsOrder() throws Exception {
        Result result = SiteApi.homeContent(VodConfig.get().getSite("json"));
        assertEquals("电视剧", result.getTypes().get(0).getTypeName());
        assertEquals(2, result.getList().size());
        assertEquals("1", result.getList().get(0).getId()); assertEquals("2", result.getList().get(1).getId());
        assertEquals(base + "/2.jpg", result.getList().get(1).getPic());
        assertEquals("2", lastQuery.get("ids")); assertEquals("retained", lastQuery.get("token"));
    }

    public void testPictureEnrichmentFailureKeepsSearchResults() throws Exception {
        failPictures = true;
        Result result = SiteApi.searchContent(VodConfig.get().getSite("json"), "测试 电影", false, "2");
        assertEquals(2, result.getList().size()); assertEquals("2", result.getList().get(1).getId());
        assertEquals(2, result.getPage()); assertEquals(3, result.getPageCount().intValue());
    }

    public void testPictureResponsesCannotReorderOrDeleteItems() throws Exception {
        Result list = Result.fromJson("{\"page\":2,\"pagecount\":3,\"list\":[{\"vod_id\":\"1\"},{\"vod_id\":\"missing\"},{\"vod_id\":\"2\"}]}");
        SiteApi.fetchPic(VodConfig.get().getSite("json"), list);
        assertEquals(3, list.getList().size()); assertEquals("1", list.getList().get(0).getId());
        assertEquals("missing", list.getList().get(1).getId()); assertEquals("2", list.getList().get(2).getId());
        assertEquals(base + "/1.jpg", list.getList().get(0).getPic()); assertEquals(2, list.getPage());
    }

    public void testJsonCategoryPaginationSurvivesHostConversion() throws Exception { verifyCategory("json"); }
    public void testXmlCategoryPaginationSurvivesHostConversion() throws Exception { verifyCategory("xml"); }
    private void verifyCategory(String key) throws Exception {
        Result result = SiteApi.categoryContent(key, "7", "2", true, new HashMap<>());
        assertEquals(key.equals("xml") ? "videolist" : "detail", lastQuery.get("ac"));
        assertEquals("7", lastQuery.get("t")); assertEquals("2", lastQuery.get("pg"));
        assertEquals(2, result.getPage()); assertEquals(3, result.getPageCount().intValue());
        assertEquals(2, result.getLimit()); assertEquals(5, result.getTotal());
        AbsXml host = new com.google.gson.Gson().fromJson(result.toString(), AbsJson.class).toAbsXml();
        assertEquals(2, host.movie.page); assertEquals(3, host.movie.pagecount);
        assertEquals(2, host.movie.pagesize); assertEquals(5, host.movie.recordcount);
        assertEquals(7, host.movie.videoList.get(0).tid);
    }

    public void testCollectionActionReplacesOldQueryAndPreservesToken() throws Exception {
        VodConfig.get().accept(base + "/query-config", "query", JsonParser.parseString(
                "{\"sites\":[{\"key\":\"query\",\"type\":1,\"api\":\"" + base + "/json?token=retained&ac=list&pg=99\"}]}").getAsJsonObject());
        SiteApi.categoryContent("query", "7", "2", false, new HashMap<>());
        assertEquals("retained", lastQuery.get("token")); assertEquals("detail", lastQuery.get("ac"));
        assertEquals("2", lastQuery.get("pg")); assertEquals(1, actionCount);
    }

    public void testJsonAndXmlSearchUseCollectionActionsAndPaging() throws Exception {
        for (String key : new String[]{"json", "xml"}) {
            Site site = VodConfig.get().getSite(key);
            SiteApi.searchContent(site, "中文 空格&test", false, "1");
            assertEquals("中文 空格&test", searchQuery.get("wd")); assertEquals("1", searchQuery.get("pg"));
            assertEquals(key.equals("xml") ? "videolist" : "detail", searchQuery.get("ac"));
            SiteApi.searchContent(site, "第二页", false, "2");
            assertEquals("2", searchQuery.get("pg"));
        }
    }

    public void testJsonAndXmlDetailRetainMultipleLinesAndDollarInUrl() throws Exception {
        for (String key : new String[]{"json", "xml"}) {
            Result result = SiteApi.detailContent(key, "1");
            assertEquals(2, result.getVod().getFlags().size());
            assertEquals(2, result.getVod().getFlags().get(0).getEpisodes().size());
            assertEquals(base + "/v.mp4?token=a$b", result.getVod().getFlags().get(0).getEpisodes().get(0).getUrl());
            assertEquals("web", result.getVod().getFlags().get(1).getFlag());
        }
    }

    public void testHostDetailDoesNotTruncateDollarInPlaybackUrl() throws Exception {
        SourceViewModel model = new SourceViewModel();
        CountDownLatch done = new CountDownLatch(1); AbsXml[] received = {null};
        androidx.lifecycle.Observer<AbsXml> observer = value -> { received[0] = value; done.countDown(); };
        getInstrumentation().runOnMainSync(() -> { model.detailResult.observeForever(observer); model.getDetail("json", "1"); });
        try {
            assertTrue(done.await(10, TimeUnit.SECONDS)); assertNotNull(received[0]);
            assertEquals(base + "/v.mp4?token=a$b", received[0].movie.videoList.get(0).urlBean.infoList.get(0).beanList.get(0).url);
        } finally { getInstrumentation().runOnMainSync(() -> model.detailResult.removeObserver(observer)); }
    }

    public void testDirectMediaBypassesParserAndWebLineUsesParser() throws Exception {
        Result direct = SiteApi.playerContent("json", "direct", base + "/v.mp4");
        assertFalse(direct.needParse()); assertEquals("site", direct.getHeader().get("X-Site"));
        Result web = SiteApi.playerContent("json", "web", "https://cms.example/watch/1");
        assertTrue(web.needParse()); assertTrue(web.isUseParse());
    }

    public void testShortJsonParserResultMergesNestedAndInheritedHeaders() throws Exception {
        Result request = SiteApi.playerContent("json", "web", "https://cms.example/watch/1");
        Parse selected = VodConfig.get().getParse("fixture");
        Map<String, String> original = new HashMap<>(selected.getHeader());
        Parsed parsed = parse(request, selected);
        assertEquals(base + "/v.mp4", parsed.url);
        assertEquals("site", parserHeaders.get("x-site")); assertEquals("parser", parserHeaders.get("x-parse"));
        assertEquals("ParserAgent", parserHeaders.get("user-agent"));
        assertEquals("session=ok", parsed.headers.get("Cookie"));
        assertEquals("https://cms.example/", parsed.headers.get("Referer"));
        assertEquals("https://cms.example", parsed.headers.get("Origin"));
        assertEquals("site", parsed.headers.get("X-Site"));
        assertEquals(original, selected.getHeader());
    }

    public void testHttpErrorCannotReturnSuccessfulPlaybackOrEmptyCatalogue() throws Exception {
        assertNull(parse(Result.objectFrom("{\"url\":\"https://cms.example/watch\"}"), Parse.get(1, base + "/denied?url=")).url);
        Site denied = Site.objectFrom(JsonParser.parseString("{\"key\":\"denied\",\"type\":1,\"api\":\"" + base + "/denied\"}"), "");
        try { SiteApi.homeContent(denied); fail("HTTP403 must be an error"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("403")); }
    }

    public void testAggregateJsonParserRetainsPerLineHeaders() throws Exception {
        Result request = SiteApi.playerContent("json", "web", "https://cms.example/watch/1");
        Parsed parsed = parse(request, Parse.get(4, ""));
        assertEquals(base + "/v.mp4", parsed.url);
        assertEquals("site", parserHeaders.get("x-site")); assertEquals("parser", parserHeaders.get("x-parse"));
        assertEquals("session=ok", parsed.headers.get("Cookie"));
    }

    public void testAggregateWebParserRetainsCredentialsCookieAndQuery() throws Exception {
        JsonObject config = JsonParser.parseString("{\"parses\":[{\"name\":\"web-fixture\",\"type\":0,\"url\":\"" + base + "/web?parser-token=retained&url=\",\"ext\":{\"header\":{\"X-Parse\":\"parser\",\"Cookie\":\"web-session=ok\"}}}]}").getAsJsonObject();
        VodConfig.get().accept(base + "/web-config", "", config);
        Parsed parsed = parse(Result.objectFrom("{\"url\":\"https://cms.example/watch\",\"header\":{\"X-Site\":\"site\"}}"), Parse.get(4, ""));
        assertEquals(base + "/v.mp4?fixture=web", parsed.url);
        assertEquals("site", parserHeaders.get("x-site")); assertEquals("parser", parserHeaders.get("x-parse"));
        assertEquals("site", parsed.headers.get("X-Site")); assertEquals("parser", parsed.headers.get("X-Parse"));
        assertTrue(parsed.headers.get("Cookie").contains("web-session=ok"));
    }

    public void testRemoteTypeFourStillUsesItsOwnExtensionProtocol() throws Exception {
        JsonObject config = JsonParser.parseString("{\"sites\":[{\"key\":\"remote\",\"type\":4,\"api\":\"" + base + "/remote\"}]}").getAsJsonObject();
        VodConfig.get().accept(base + "/remote-config", "remote", config);
        HashMap<String, String> filter = new HashMap<>(); filter.put("year", "2026");
        SiteApi.categoryContent("remote", "7", "2", true, filter);
        assertTrue(lastQuery.containsKey("ext")); assertFalse(lastQuery.containsKey("f"));
        assertEquals("detail", lastQuery.get("ac")); assertEquals("2", lastQuery.get("pg"));
        SiteApi.searchContent(VodConfig.get().getSite("remote"), "搜索", false, "1");
        assertFalse(searchQuery.containsKey("ac")); assertFalse(searchQuery.containsKey("pg"));
    }

    public void testInvalidCollectionBodyIsAnErrorAndEmptyListIsValid() {
        try { Result.fromType(1, "<html>login</html>"); fail("HTML must not become an empty list"); }
        catch (IllegalArgumentException expected) { }
        assertTrue(Result.fromType(1, "{\"code\":1,\"list\":[]}").getList().isEmpty());
        assertEquals(1, Result.fromType(0, json(Collections.singletonMap("ids", "1"))).getList().size());
    }

    public void testSnifferSharesFormatsRulesAndRejectsWrapperPages() {
        for (String suffix : new String[]{"m3u8", "mp4", "mpd", "mkv", "flv", "avi", "rm", "wmv", "mpg", "mp3", "m4a", "aac", "webm", "mov"}) {
            String url = "https://a.co/v." + suffix.toUpperCase() + "?token=a";
            assertTrue(url, Sniffer.isVideoFormat(url)); assertTrue(url, DefaultConfig.isVideoFormat(url));
        }
        assertFalse(Sniffer.isVideoFormat("https://a.co/parse?url=https://b.co/v.mp4"));
        assertFalse(Sniffer.isVideoFormat("https://a.co/parse?url=https%3A%2F%2Fb.co%2Fv.mp4"));
        assertFalse(Sniffer.isVideoFormat("https://a.co/v.mp4.html"));
        JsonObject config = JsonParser.parseString("{\"rules\":[{\"hosts\":[\"a.co\"],\"regex\":[\"/media\"],\"exclude\":[\"advert\"]}]}").getAsJsonObject();
        VodConfig.get().accept(base + "/rules", "", config);
        assertTrue(DefaultConfig.isVideoFormat("https://a.co/media?id=1"));
        assertFalse(DefaultConfig.isVideoFormat("https://a.co/advert.mp4"));
    }

    private static class Parsed { String url; Map<String, String> headers; }
    private Parsed parse(Result request, Parse selected) throws Exception {
        CountDownLatch done = new CountDownLatch(1); Parsed parsed = new Parsed();
        ParseJob job = ParseJob.create(new ParseCallback() {
            @Override public void onParseSuccess(Map<String, String> headers, String url, String from) { parsed.url = url; parsed.headers = headers; done.countDown(); }
            @Override public void onParseError() { done.countDown(); }
        }).start(request, selected);
        try { assertTrue("Parser callback timed out", done.await(10, TimeUnit.SECONDS)); return parsed; }
        finally { getInstrumentation().runOnMainSync(job::stop); }
    }
}
