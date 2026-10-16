package com.kukuqi.tvbox.osc;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.test.InstrumentationTestCase;
import com.kukuqi.tvbox.engine.runtime.RuntimeModule;
import com.kukuqi.tvbox.engine.runtime.RuntimeModules;
import com.kukuqi.tvbox.osc.util.RuntimeDownload;
import fi.iki.elonen.NanoHTTPD;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Separate APK loading and untrusted/partial download regressions, using a local HTTP fixture. */
public class RuntimeModuleRegressionTest extends InstrumentationTestCase {
    private Context host;
    private NanoHTTPD server;
    private String base;
    private volatile boolean corruptHash, truncate;
    @Override protected void setUp() throws Exception {
        super.setUp(); host = getInstrumentation().getTargetContext();
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                try {
                    if (session.getUri().equals("/catalog.json")) return newFixedLengthResponse(Response.Status.OK, "application/json", catalog().toString());
                    if (session.getUri().equals("/source.cjs")) return newFixedLengthResponse(Response.Status.OK, "text/javascript", "module.exports={home:async()=>({list:[{vod_name:'remote-node-ready'}]})};");
                    RuntimeModule requested = java.util.Arrays.stream(RuntimeModule.values()).filter(module -> session.getUri().equals("/" + module.id + ".apk")).findFirst().orElseThrow();
                    File file = fixture(requested);
                    if (truncate) return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", "incomplete");
                    return newFixedLengthResponse(Response.Status.OK, "application/vnd.android.package-archive", new FileInputStream(file), file.length());
                } catch (Exception e) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString()); }
            }
        };
        server.start(); base = "http://127.0.0.1:" + server.getListeningPort();
    }
    @Override protected void tearDown() throws Exception { server.stop(); super.tearDown(); }
    private File fixture(RuntimeModule module) { return new File("/sdcard/Download/tvbox-runtime-fixtures/" + module.id + ".apk"); }
    private JSONObject catalog() throws Exception {
        JSONArray modules = new JSONArray();
        for (RuntimeModule module : RuntimeModule.values()) {
            File file = fixture(module);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) { byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
            StringBuilder sha = new StringBuilder(); for (byte b : digest.digest()) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            JSONObject artifact = new JSONObject().put("abi", "arm64-v8a").put("minSdk", 24).put("size", file.length())
                    .put("sha256", corruptHash ? "0".repeat(64) : sha.toString()).put("url", base + "/" + module.id + ".apk");
            modules.put(new JSONObject().put("id", module.id).put("packageName", module.packageName(host)).put("compatibilityId", module.contract())
                    .put("versionCode", 1).put("versionName", "1.0.0").put("artifacts", new JSONArray().put(artifact)));
        }
        return new JSONObject().put("schema", 1).put("modules", modules);
    }
    public void testMissingModulesDoNotInitializeAtStartup() throws Exception {
        assertNotNull(com.kukuqi.tvbox.engine.api.loader.BaseLoader.get());
        assertNotNull(com.kukuqi.tvbox.engine.player.extractor.Source.get());
        com.kukuqi.tvbox.engine.api.loader.BaseLoader.get().clear();
        com.kukuqi.tvbox.engine.player.extractor.Source.get().stop();
        for (RuntimeModule module : RuntimeModule.values()) {
            assertEquals("未安装 · 点击下载", RuntimeModules.status(host, module));
            assertFalse(RuntimeModules.isLoaded(module));
            try { RuntimeModules.create(host, module); fail("Missing runtime was accepted"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("模块管理")); }
        }
    }
    public void testDownloadsVerifyAllModuleApks() throws Exception {
        for (RuntimeModule module : RuntimeModule.values()) {
            File apk = RuntimeDownload.download(host, module, base + "/catalog.json", value -> {});
            assertEquals(fixture(module).length(), apk.length());
            assertFalse(new File(apk.getParentFile(), module.id + ".part").exists());
        }
    }
    public void testCorruptDownloadRejectedAndCanRetry() throws Exception {
        corruptHash = true;
        try { RuntimeDownload.download(host, RuntimeModule.QUICKJS, base + "/catalog.json", value -> {}); fail("Corrupt module accepted"); }
        catch (SecurityException expected) { assertTrue(expected.getMessage().contains("SHA-256")); }
        assertFalse(new File(host.getCacheDir(), "runtime-downloads/quickjs.part").exists());
        corruptHash = false;
        assertTrue(RuntimeDownload.download(host, RuntimeModule.QUICKJS, base + "/catalog.json", value -> {}).isFile());
    }
    public void testIncompleteDownloadRejected() throws Exception {
        truncate = true;
        try { RuntimeDownload.download(host, RuntimeModule.PYTHON, base + "/catalog.json", value -> {}); fail("Truncated module accepted"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("不完整")); }
        assertFalse(new File(host.getCacheDir(), "runtime-downloads/python.part").exists());
    }
    @SuppressWarnings("deprecation")
    public void testForeignSignerAndContractRejected() throws Exception {
        PackageInfo info = host.getPackageManager().getPackageArchiveInfo(fixture(RuntimeModule.QUICKJS).getAbsolutePath(), PackageManager.GET_META_DATA | PackageManager.GET_SIGNATURES);
        assertNotNull(info);
        info.signatures = new android.content.pm.Signature[]{new android.content.pm.Signature("01020304")};
        try { RuntimeModules.verify(host, RuntimeModule.QUICKJS, info); fail("Foreign signer accepted"); }
        catch (SecurityException expected) { assertTrue(expected.getMessage().contains("签名")); }
        info.applicationInfo.metaData.putString("tvbox.runtime.contract", "other-api");
        try { RuntimeModules.verify(host, RuntimeModule.QUICKJS, info); fail("Foreign contract accepted"); }
        catch (SecurityException expected) { assertTrue(expected.getMessage().contains("接口")); }
    }
    public void testModulePageDownloadsAndOffersVerifiedApkToInstaller() throws Exception {
        android.app.Activity activity = null;
        android.content.IntentFilter filter = new android.content.IntentFilter(android.content.Intent.ACTION_VIEW);
        filter.addDataType("application/vnd.android.package-archive"); filter.addDataScheme("content");
        android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(filter,
                new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true);
        try {
            getInstrumentation().runOnMainSync(() -> {
                com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart = true;
            });
            activity = getInstrumentation().startActivitySync(new android.content.Intent(host, com.kukuqi.tvbox.osc.ui.activity.MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(com.kukuqi.tvbox.osc.ui.activity.MainActivity.EXTRA_START_DESTINATION, R.id.navigation_dashboard));
            android.app.Activity screen = activity;
            getInstrumentation().runOnMainSync(() -> com.kukuqi.tvbox.osc.ui.dialog.FeatureSettingsDialog.modules(screen, () -> base + "/catalog.json"));
            boolean clicked = false;
            for (int i = 0; i < 100 && !clicked; i++) {
                android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
                if (root != null) for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("Python ·")) {
                    if (node.isClickable()) { clicked = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); break; }
                }
                if (!clicked) Thread.sleep(100);
            }
            assertTrue("Module download row could not be clicked", clicked);
            for (int i = 0; i < 200 && monitor.getHits() == 0; i++) Thread.sleep(100);
            assertEquals("Verified APK was not offered to Android installer", 1, monitor.getHits());
            File apk = new File(host.getCacheDir(), "runtime-downloads/python.apk");
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(host, host.getPackageName() + ".fileprovider", apk);
            try (java.io.InputStream in = host.getContentResolver().openInputStream(uri)) { assertNotNull(in); assertEquals('P', in.read()); assertEquals('K', in.read()); }
        } finally {
            getInstrumentation().removeMonitor(monitor);
            android.app.Activity screen = activity;
            getInstrumentation().runOnMainSync(() -> {
                if (screen != null) screen.finish();
            });
        }
    }
    public void testInstalledModulesShareHostApiAndLoadNativeThunder() throws Exception {
        for (RuntimeModule module : RuntimeModule.values()) {
            assertEquals("已安装 1.0.0", RuntimeModules.status(host, module));
            ClassLoader loader = RuntimeModules.loader(host, module);
            assertSame(com.github.catvod.crawler.Spider.class, loader.loadClass("com.github.catvod.crawler.Spider"));
            assertNotSame(host.getClassLoader(), loader.loadClass(module.entry).getClassLoader());
        }
        // A local torrent exercises native Thunder parsing without starting a network download.
        File torrent = new File(host.getCacheDir(), "runtime-fixture.torrent");
        // Thunder intentionally filters files <= 30 MiB. Only metadata is generated, no video payload.
        java.nio.file.Files.writeString(torrent.toPath(), "d4:infod6:lengthi33554432e4:name9:video.mp412:piece lengthi33554432e6:pieces20:01234567890123456789ee");
        Object thunder = RuntimeModules.create(host, RuntimeModule.THUNDER);
        List<?> media = (List<?>) RuntimeModules.call(thunder, "parse", new Class<?>[]{String.class}, "file://" + torrent.getAbsolutePath());
        assertEquals(1, media.size());
        RuntimeModules.call(thunder, "exit", new Class<?>[0]);
    }
    public void testNodeSourcesExecuteCommonJsEsmAndBinaryProxy() throws Exception {
        File source = new File(host.getFilesDir(), "node-validation.cjs");
        java.nio.file.Files.writeString(source.toPath(), "const {createHash}=require('node:crypto'); let ext; module.exports={init:async e=>{ext=e}, home:async()=>({class:[{type_id:'1',type_name:ext}],node:process.versions.node,hash:createHash('sha256').update('ok').digest('hex')}),category:async(tid,pg,filter,extra)=>({list:[{vod_id:tid,vod_name:pg+extra.q}]}),detail:async ids=>({list:ids.map(id=>({vod_id:id}))}),search:async(k,q,pg)=>({list:[{vod_name:k+pg}]}),play:async(f,id)=>({parse:0,url:id}),proxy:async p=>[200,'application/octet-stream',Buffer.from([0,255,65]),{'X-Fixture':'yes'}]};");
        com.kukuqi.tvbox.engine.api.loader.BaseLoader loader = com.kukuqi.tvbox.engine.api.loader.BaseLoader.get();
        try {
            com.github.catvod.crawler.Spider spider = loader.getSpider("node-validation", "nodejs:" + source.getAbsolutePath(), "node-ready", "");
            JSONObject home = new JSONObject(spider.homeContent(true));
            assertEquals("node-ready", home.getJSONArray("class").getJSONObject(0).getString("type_name"));
            assertTrue(home.getString("node").startsWith("24."));
            assertEquals(64, home.getString("hash").length());
            assertEquals("2value", new JSONObject(spider.categoryContent("a", "2", true, new java.util.HashMap<>(java.util.Map.of("q", "value")))).getJSONArray("list").getJSONObject(0).getString("vod_name"));
            assertEquals("id", new JSONObject(spider.detailContent(java.util.List.of("id"))).getJSONArray("list").getJSONObject(0).getString("vod_id"));
            assertEquals("keyword3", new JSONObject(spider.searchContent("keyword", false, "3")).getJSONArray("list").getJSONObject(0).getString("vod_name"));
            assertEquals("https://example.org/video.mp4", new JSONObject(spider.playerContent("", "https://example.org/video.mp4", java.util.List.of())).getString("url"));
            loader.setRecent("node-validation", "nodejs:" + source.getAbsolutePath(), "");
            Object[] proxy = loader.proxy(new java.util.HashMap<>(java.util.Map.of("do", "nodejs")));
            assertEquals(200, proxy[0]);
            try (java.io.InputStream input = (java.io.InputStream) proxy[2]) { assertEquals(0,input.read());assertEquals(255,input.read());assertEquals(65,input.read()); }
            File esm = new File(host.getFilesDir(), "node-validation.mjs");
            java.nio.file.Files.writeString(esm.toPath(), "export default {home:async()=>({list:[{vod_name:'esm-ready'}]})};");
            assertTrue(loader.getSpider("node-esm", esm.getAbsolutePath(), "", "").homeContent(false).contains("esm-ready"));
            assertTrue(loader.getSpider("node-remote", "nodejs:" + base + "/source.cjs", "", "").homeContent(false).contains("remote-node-ready"));
            loader.clear();
            assertTrue(loader.getSpider("node-restart", "nodejs:" + base + "/source.cjs", "", "").homeContent(false).contains("remote-node-ready"));
        } finally { loader.clear(); source.delete(); }
    }
    public void testBdjRuntimePreparesJreAndLoadsJvm() throws Exception {
        String home = com.kukuqi.tvbox.engine.runtime.BdjSupport.prepare();
        assertTrue(new File(home, "lib/rt.jar").isFile());
        assertTrue(new File(home, "lib/aarch64/server/libjvm.so").isFile());
        assertEquals(home, com.kukuqi.tvbox.engine.runtime.BdjSupport.prepare());
        assertTrue(new File(new File(home).getParentFile(), "libbluray-j2se-1.4.1.jar").isFile());
    }
    public void testBdjJvmExecutesJavaInDisposableProcess() throws Exception {
        String home = com.kukuqi.tvbox.engine.runtime.BdjSupport.prepare();
        Object runtime = RuntimeModules.create(host, RuntimeModule.BDJ);
        String version = (String) RuntimeModules.call(runtime, "probe", new Class<?>[]{String.class, String.class}, home,
                new File(new File(home).getParentFile(), "libbluray-j2se-1.4.1.jar").getAbsolutePath());
        assertTrue("Unexpected JRE version: " + version, version.startsWith("1.8.0_"));
    }
    public void testCatalogMigrationClearsFixtureAndKeepsHttps() throws Exception {
        String previous = com.orhanobut.hawk.Hawk.get(RuntimeDownload.CATALOG_KEY, "");
        try {
            com.orhanobut.hawk.Hawk.put(RuntimeDownload.CATALOG_KEY, "http://127.0.0.1:43199/catalog.json");
            RuntimeDownload.migrateCatalog();
            assertEquals(BuildConfig.RUNTIME_CATALOG_URL, RuntimeDownload.catalogUrl());
            com.orhanobut.hawk.Hawk.put(RuntimeDownload.CATALOG_KEY, "https://example.org/catalog.json");
            RuntimeDownload.migrateCatalog();
            assertEquals("https://example.org/catalog.json", RuntimeDownload.catalogUrl());
        } finally {
            if (previous.isEmpty() || previous.startsWith("http://127.0.0.1:")) com.orhanobut.hawk.Hawk.delete(RuntimeDownload.CATALOG_KEY);
            else com.orhanobut.hawk.Hawk.put(RuntimeDownload.CATALOG_KEY, previous);
        }
    }
}
