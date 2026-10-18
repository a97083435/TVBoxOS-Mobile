package com.kukuqi.tvbox.osc;

import android.test.InstrumentationTestCase;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kukuqi.tvbox.engine.api.SiteApi;
import com.kukuqi.tvbox.engine.api.config.VodConfig;
import com.kukuqi.tvbox.engine.bean.Result;
import com.kukuqi.tvbox.osc.util.SourceManager;
import fi.iki.elonen.NanoHTTPD;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Local protocol fixtures exercise actual decoding, runtime loading and host import. */
public class MultiChannelSourceRegressionTest extends InstrumentationTestCase {
    private NanoHTTPD server;
    private String base;
    private final AtomicInteger attempts = new AtomicInteger();
    @Override protected void setUp() throws Exception {
        super.setUp();
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                String path = session.getUri();
                if (path.equals("/redirect")) {
                    Response response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
                    response.addHeader("Location", base + "/nested/depot.json"); return response;
                }
                if (path.equals("/nested/depot.json")) return newFixedLengthResponse("abcdefgh**" + android.util.Base64.encodeToString(
                        "{\"urls\":[{\"name\":\"甲\",\"url\":\"./one.json\"},{\"name\":\"乙\",\"url\":\"../two.json\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
                if (path.equals("/nested/one.json")) return newFixedLengthResponse("{\"sites\":[{\"key\":\"one\",\"type\":3,\"api\":\"./source.js?module=./lib\",\"ext\":{\"url\":\"./template\",\"token\":\"a../b\"}}],\"rules\":[{\"regex\":[\"./keep\"]}]}");
                if (path.equals("/paths.json")) return newFixedLengthResponse("{\"spider\":\"../spider.jar;md5;./digest.txt\",\"sites\":[{\"api\":\"nodejs:./source.mjs\",\"ext\":{\"token\":\"./untouched\"}}],\"storeHouse\":[{\"sourceUrl\":\"../depot.json\"}],\"rules\":[{\"regex\":[\"../token\"]}]}");
                if (path.equals("/document.js")) return newFixedLengthResponse("const items=[1,2,3].map(n=>({vod_id:String(n),vod_name:'影片'+n}));\nconst initial=page(items,1,2);\nexport default ()=>({\n init:async()=>{},\n home:()=>({class:[{type_id:'1',type_name:'内嵌',filters:[{key:'area',name:'地区',init:'cn',value:[{n:'中国',v:'cn'}]}]},{type_id:'2',type_name:'顶层',filters:[{key:'sort',name:'排序',init:'inner',value:[{n:'内嵌',v:'inner'}]}]}],filters:{'2':[{key:'sort',name:'排序',init:'outer',value:[{n:'顶层',v:'outer'}]}]}}),\n homeVod:()=>initial,\n category:async(tid,pg,filter,extend)=>page(items.map(v=>({...v,vod_name:extend.area||v.vod_name})),pg,2),\n search:async(key,quick,pg)=>page(items,pg,2),\n play:async(flag,id)=>({parse:0,url:id}),\n destroy:()=>{}\n});");
                if (path.equals("/document.py")) return newFixedLengthResponse("from base.spider import Spider as Base\nclass Spider(Base):\n def init(self,extend=''): pass\n def homeContent(self,filter): return {'class':[{'type_id':'1','type_name':'分页'}]}\n def homeVideoContent(self): return {'list':[]}\n def categoryContent(self,tid,pg,filter,extend): return self.page([{'vod_id':str(n),'vod_name':'影片'+str(n)} for n in [1,2,3]],pg,2)\n def destroy(self): pass\n");
                if (path.equals("/retry.py")) {
                    boolean fail = attempts.incrementAndGet() == 1;
                    return newFixedLengthResponse("from base.spider import Spider as Base\nclass Spider(Base):\n def init(self,extend=''): " + (fail ? "raise Exception('temporary fixture failure')" : "pass")
                            + "\n def homeContent(self,filter): return {'class':[{'type_id':'1','type_name':'重试成功'}]}\n def homeVideoContent(self): return {'list':[]}\n def destroy(self): pass\n");
                }
                if (path.endsWith("/spider.py")) {
                    String title = path.startsWith("/first/") ? "第一站" : "第二站";
                    return newFixedLengthResponse("from base.spider import Spider as Base\nsite_title='" + title + "'\nclass Spider(Base):\n def init(self,extend=''): pass\n def homeContent(self,filter): return {'class':[{'type_id':'1','type_name':site_title}]}\n def homeVideoContent(self): return {'list':[]}\n def destroy(self): pass\n");
                }
                if (path.equals("/retry.js")) {
                    boolean fail = attempts.incrementAndGet() == 1;
                    return newFixedLengthResponse("export default {init:async()=>{" + (fail ? "throw new Error('temporary fixture failure');" : "")
                            + "},home:async()=>JSON.stringify({class:[{type_id:'1',type_name:'重试成功'}]}),homeVod:async()=>JSON.stringify({list:[]}),destroy:async()=>{}};");
                }
                return newFixedLengthResponse("export default {init:async()=>{},home:async()=>JSON.stringify({class:[]}),homeVod:async()=>JSON.stringify({list:[]}),play:async(flag,id)=>JSON.stringify({parse:0,url:id,header:{'user-agent':'PlayerAgent','X-Player':'response'}}),destroy:async()=>{}};");
            }
        };
        server.start(); base = "http://127.0.0.1:" + server.getListeningPort();
    }
    @Override protected void tearDown() throws Exception {
        com.kukuqi.tvbox.engine.api.loader.BaseLoader.get().clear();
        server.stop(); super.tearDown();
    }

    public void testDecodedDepotKeepsAllChoicesAndRedirectBase() throws Exception {
        JsonObject document = read(base + "/redirect", false);
        assertEquals(2, document.getAsJsonArray("urls").size());
        assertEquals(base + "/nested/one.json", document.getAsJsonArray("urls").get(0).getAsJsonObject().get("url").getAsString());
        assertEquals(base + "/two.json", document.getAsJsonArray("urls").get(1).getAsJsonObject().get("url").getAsString());
    }
    public void testDepotPlaybackResolvesResourcesButPreservesScriptOptions() throws Exception {
        JsonObject document = read(base + "/redirect", true);
        JsonObject site = document.getAsJsonArray("sites").get(0).getAsJsonObject();
        assertEquals(base + "/nested/source.js?module=./lib", site.get("api").getAsString());
        assertEquals(base + "/nested/template", site.getAsJsonObject("ext").get("url").getAsString());
        assertEquals("a../b", site.getAsJsonObject("ext").get("token").getAsString());
        assertEquals("./keep", document.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("regex").get(0).getAsString());
    }
    public void testDecoderPreservesRegexAndSupportsRelativeNodeAndMd5Resource() throws Exception {
        JsonObject document = read(base + "/paths.json", false);
        assertEquals(base + "/spider.jar;md5;" + base + "/digest.txt", document.get("spider").getAsString());
        assertEquals("nodejs:" + base + "/source.mjs", document.getAsJsonArray("sites").get(0).getAsJsonObject().get("api").getAsString());
        assertEquals(base + "/depot.json", document.getAsJsonArray("storeHouse").get(0).getAsJsonObject().get("sourceUrl").getAsString());
        assertEquals("../token", document.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("regex").get(0).getAsString());
    }
    public void testJavascriptInitializationFailureCanRetryWithoutReloadingConfig() throws Exception { retry("js"); }
    public void testPythonInitializationFailureCanRetryWithoutReloadingConfig() throws Exception { retry("py"); }
    private void retry(String language) throws Exception {
        configure("/retry." + language);
        try { SiteApi.homeContent(VodConfig.get().getSite("fixture")); fail("Initialization must fail visibly"); }
        catch (IllegalStateException expected) { /* Failed spiders must not enter the cache. */ }
        assertEquals("重试成功", SiteApi.homeContent(VodConfig.get().getSite("fixture")).getTypes().get(0).getTypeName());
        assertEquals(2, attempts.get());
    }
    public void testScriptPlayerUsesExplicitHeadersWithoutInheritingSourceCredentials() throws Exception {
        configure("/source.js");
        Result result = SiteApi.playerContent("fixture", "direct", base + "/v.mp4");
        assertFalse(result.getHeader().containsKey("X-Auth"));
        assertEquals("response", result.getHeader().get("X-Player"));
        assertEquals("PlayerAgent", result.getHeader().get("user-agent"));
        assertEquals(1, result.getHeader().keySet().stream().filter(key -> key.equalsIgnoreCase("User-Agent")).count());
    }
    public void testSameFilenamePythonSitesStayIsolatedAcrossAlternatingCalls() throws Exception {
        VodConfig.get().accept(base + "/multi.json", "first", JsonParser.parseString(
                "{\"sites\":[{\"key\":\"first\",\"type\":3,\"api\":\"" + base + "/first/spider.py?mode=one\"},{\"key\":\"second\",\"type\":3,\"api\":\"" + base + "/second/spider.py?mode=two\"}]}").getAsJsonObject());
        assertEquals("第一站", SiteApi.homeContent(VodConfig.get().getSite("first")).getTypes().get(0).getTypeName());
        assertEquals("第二站", SiteApi.homeContent(VodConfig.get().getSite("second")).getTypes().get(0).getTypeName());
        assertEquals("第一站", SiteApi.homeContent(VodConfig.get().getSite("first")).getTypes().get(0).getTypeName());
    }
    public void testJavascriptObjectAndPromiseResultsUseDocumentedPaging() throws Exception {
        configure("/document.js");
        Result home = SiteApi.homeContent(VodConfig.get().getSite("fixture"));
        assertEquals(2, home.getList().size());
        java.util.HashMap<String, String> filters = new java.util.HashMap<>(); filters.put("area", "cn");
        Result second = SiteApi.categoryContent("fixture", "1", "2", true, filters);
        assertEquals(2, second.getPage()); assertEquals(2, second.getPageCount().intValue());
        assertEquals(3, second.getTotal()); assertEquals(1, second.getList().size());
        assertEquals("3", second.getList().get(0).getId()); assertEquals("cn", second.getList().get(0).getName());
        assertEquals("3", SiteApi.searchContent(VodConfig.get().getSite("fixture"), "影片", false, "2").getList().get(0).getId());
        assertTrue(SiteApi.categoryContent("fixture", "1", "3", true, filters).getList().isEmpty());
    }
    public void testEmptyPlayerHeaderFallsBackToWholeSiteHeader() throws Exception {
        configure("/document.js");
        Result result = SiteApi.playerContent("fixture", "direct", base + "/v.mp4");
        assertEquals("fixture-auth", result.getHeader().get("X-Auth"));
        assertEquals("SiteAgent", result.getHeader().get("User-Agent"));
    }
    public void testPythonDocumentedPageHelperCrossesRuntimeBridge() throws Exception {
        configure("/document.py");
        Result second = SiteApi.categoryContent("fixture", "1", "2", true, new java.util.HashMap<>());
        assertEquals(2, second.getPage()); assertEquals(2, second.getPageCount().intValue());
        assertEquals(3, second.getTotal()); assertEquals(1, second.getList().size());
        assertEquals("3", second.getList().get(0).getId());
        assertTrue(SiteApi.categoryContent("fixture", "1", "3", true, new java.util.HashMap<>()).getList().isEmpty());
    }
    public void testClassFiltersAndInitialValuesReachHostAndReset() throws Exception {
        configure("/document.js");
        com.kukuqi.tvbox.osc.viewmodel.SourceViewModel model = new com.kukuqi.tvbox.osc.viewmodel.SourceViewModel();
        CountDownLatch done = new CountDownLatch(1);
        com.kukuqi.tvbox.osc.bean.AbsSortXml[] received = {null};
        androidx.lifecycle.Observer<com.kukuqi.tvbox.osc.bean.AbsSortXml> observer = value -> { received[0] = value; done.countDown(); };
        getInstrumentation().runOnMainSync(() -> { model.sortResult.observeForever(observer); model.getSort("fixture"); });
        try {
            assertTrue(done.await(15, TimeUnit.SECONDS)); assertNotNull(received[0]);
            com.kukuqi.tvbox.osc.bean.MovieSort.SortData first = received[0].classes.sortList.get(0);
            assertEquals("cn", first.filterSelect.get("area"));
            assertEquals("outer", received[0].classes.sortList.get(1).filterSelect.get("sort"));
            first.filterSelect.put("area", "changed"); first.filterSelect.put("unused", "old"); first.resetFilters();
            assertEquals(1, first.filterSelect.size()); assertEquals("cn", first.filterSelect.get("area"));
            assertEquals("cn", SiteApi.categoryContent("fixture", first.id, "1", true, first.filterSelect).getList().get(0).getName());
        } finally { getInstrumentation().runOnMainSync(() -> model.sortResult.removeObserver(observer)); }
    }
    private void configure(String path) {
        VodConfig.get().accept(base + "/config.json", "fixture", JsonParser.parseString(
                "{\"sites\":[{\"key\":\"fixture\",\"type\":3,\"api\":\"" + base + path + "\",\"header\":{\"X-Auth\":\"fixture-auth\",\"User-Agent\":\"SiteAgent\"}}]}").getAsJsonObject());
    }
    private JsonObject read(String url, boolean resolve) throws Exception {
        CountDownLatch done = new CountDownLatch(1); String[] result = new String[2];
        SourceManager.ContentResult callback = (content, error) -> { result[0] = content; result[1] = error; done.countDown(); };
        if (resolve) SourceManager.readConfig(url, callback); else SourceManager.readDocument(url, callback);
        assertTrue(done.await(15, TimeUnit.SECONDS)); assertNotNull(result[1], result[0]);
        return JsonParser.parseString(result[0]).getAsJsonObject();
    }
}
