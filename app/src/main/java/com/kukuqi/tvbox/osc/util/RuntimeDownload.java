package com.kukuqi.tvbox.osc.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import com.kukuqi.tvbox.engine.runtime.RuntimeModule;
import com.kukuqi.tvbox.engine.runtime.RuntimeModules;
import com.kukuqi.tvbox.osc.BuildConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Download to private staging storage; verify before offering the APK to Android's installer. */
public final class RuntimeDownload {
    public static final String CATALOG_KEY = "runtime_catalog_url";
    public static final String ROUTE_KEY = "runtime_download_route";
    private static final long LIMIT = 160L * 1024 * 1024;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).callTimeout(20, TimeUnit.MINUTES).followSslRedirects(false).build();
    private static final RuntimeTransfer TRANSFER = new RuntimeTransfer(CLIENT);
    private static final RuntimeTransfer CATALOG = new RuntimeTransfer(CLIENT.newBuilder().callTimeout(25, TimeUnit.SECONDS).build());
    private RuntimeDownload() {}
    /** Old instrumentation builds accidentally persisted their short-lived fixture server. */
    public static void migrateCatalog() {
        String saved = com.orhanobut.hawk.Hawk.get(CATALOG_KEY, "");
        try {
            java.net.URI uri = java.net.URI.create(saved.trim());
            String host = uri.getHost();
            if ("http".equalsIgnoreCase(uri.getScheme()) &&
                    ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host)) &&
                    "/catalog.json".equals(uri.getPath())) com.orhanobut.hawk.Hawk.delete(CATALOG_KEY);
        } catch (IllegalArgumentException ignored) { }
    }
    public static String catalogUrl() {
        String saved = com.orhanobut.hawk.Hawk.get(CATALOG_KEY, "");
        return saved.trim().isEmpty() ? BuildConfig.RUNTIME_CATALOG_URL : saved.trim();
    }
    public static int routeMode() { return com.orhanobut.hawk.Hawk.get(ROUTE_KEY, 0); }
    public static String errorMessage(Exception failure) {
        if (failure instanceof java.net.ConnectException) return "无法连接模块服务器，请检查下载地址";
        if (failure instanceof java.net.UnknownHostException) return "无法解析模块服务器，请检查网络";
        if (failure instanceof java.net.SocketTimeoutException || failure instanceof java.io.InterruptedIOException)
            return "下载超时，可切换下载线路后重试，已下载部分会保留";
        if (failure instanceof javax.net.ssl.SSLException) return "模块服务器安全连接失败";
        return failure.getMessage() == null ? "下载失败，请重试" : failure.getMessage();
    }
    private static void checkUrl(String url) {
        if (url == null || !(url.startsWith("https://") || (BuildConfig.DEBUG && url.startsWith("http://"))))
            throw new IllegalArgumentException("模块下载地址须使用 HTTPS");
    }
    private static JSONObject catalog(String url, Consumer<String> progress) throws Exception {
        Exception last = null;
        for (String route : RuntimeTransfer.routes(url, routeMode())) {
            checkUrl(route);
            progress.accept("正在读取模块目录 · " + RuntimeTransfer.routeName(route));
            try (Response response = CATALOG.request(route); InputStream in = response.body().byteStream()) {
                byte[] data = in.readNBytes(1024 * 1024 + 1);
                if (data.length > 1024 * 1024) throw new java.io.IOException("模块目录过大");
                JSONObject catalog = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                if (catalog.getInt("schema") != 1) throw new IllegalArgumentException("不支持的模块目录版本");
                catalog.getJSONArray("modules");
                return catalog;
            } catch (Exception failure) { last = failure; }
        }
        if (last != null && "下载失败 HTTP 404".equals(last.getMessage()))
            throw new java.io.IOException("模块暂未上架，请稍后重试或检查下载地址", last);
        throw last == null ? new java.io.IOException("无法读取模块目录") : last;
    }
    /** Returns null when the verified installed module is already at least this version. */
    @SuppressWarnings("deprecation")
    public static synchronized File download(Context host, RuntimeModule module, String catalogUrl, Consumer<String> progress) throws Exception {
        if (catalogUrl == null || catalogUrl.trim().isEmpty()) throw new IllegalStateException("尚未设置模块下载地址");
        JSONObject catalog = catalog(catalogUrl.trim(), progress);
        JSONArray modules = catalog.getJSONArray("modules");
        JSONObject selected = null, artifact = null;
        for (int i = 0; i < modules.length(); i++) {
            JSONObject item = modules.getJSONObject(i);
            if (module.id.equals(item.getString("id"))) { selected = item; break; }
        }
        if (selected != null && host.getPackageName().endsWith(".corevalidation") &&
                ("com.kukuqi.tvbox.runtime." + module.id).equals(selected.getString("packageName")))
            throw new IllegalArgumentException("这是独立验证版，不能使用正式模块目录；请切换正式应用，或配置匹配的验证目录");
        if (selected == null || !module.packageName(host).equals(selected.getString("packageName")) || !module.contract().equals(selected.getString("compatibilityId")))
            throw new IllegalArgumentException("目录未提供兼容的 " + module.title + " 模块");
        JSONArray artifacts = selected.getJSONArray("artifacts");
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject item = artifacts.getJSONObject(i);
            if ("arm64-v8a".equals(item.getString("abi")) && item.getInt("minSdk") <= Build.VERSION.SDK_INT) { artifact = item; break; }
        }
        if (artifact == null) throw new IllegalArgumentException("没有适合本设备的模块");
        long size = artifact.getLong("size");
        String digest = artifact.getString("sha256");
        if (size <= 0 || size > LIMIT || !digest.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("模块校验信息无效");
        int version = selected.getInt("versionCode");
        if (version <= 0) throw new IllegalArgumentException("模块版本无效");
        try {
            if (RuntimeModules.installed(host, module).versionCode >= version) {
                progress.accept("已是最新版本");
                return null;
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
        File directory = new File(host.getCacheDir(), "runtime-downloads");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("无法创建下载目录");
        // Pin partial data to the expected content, so a new catalog cannot resume an old module build.
        File part = new File(directory, module.id + "-" + digest.toLowerCase(Locale.ROOT) + ".part");
        File[] stale = directory.listFiles((dir, name) -> name.startsWith(module.id + "-") && name.endsWith(".part") && !name.equals(part.getName()));
        if (stale != null) for (File file : stale) file.delete();
        File verified = new File(directory, module.id + ".apk");
        if (verified.isFile()) {
            try {
                verifyApk(host, module, verified, size, digest, version);
                progress.accept("安装包已就绪，等待安装");
                return verified;
            } catch (Exception invalidCache) {
                if (!verified.delete()) throw new java.io.IOException("无法清理旧模块安装包", invalidCache);
            }
        }
        Exception last = null;
        for (String route : RuntimeTransfer.routes(artifact.getString("url"), routeMode())) {
            checkUrl(route);
            progress.accept("正在连接 · " + RuntimeTransfer.routeName(route));
            try {
                TRANSFER.download(route, part, size, progress);
                progress.accept("下载完成，正在校验…");
                verifyApk(host, module, part, size, digest, version);
                if (verified.exists() && !verified.delete()) throw new java.io.IOException("无法替换模块安装包");
                if (!part.renameTo(verified)) throw new java.io.IOException("无法完成模块下载");
                progress.accept("校验通过，等待安装");
                return verified;
            } catch (Exception failure) {
                last = failure;
                if (failure instanceof SecurityException || part.length() >= size) part.delete();
                if (Thread.currentThread().isInterrupted()) throw failure;
            }
        }
        throw last == null ? new java.io.IOException("模块下载失败") : last;
    }

    @SuppressWarnings("deprecation")
    private static void verifyApk(Context host, RuntimeModule module, File apk, long size, String digest, int version) throws Exception {
        if (apk.length() != size) throw new SecurityException("模块安装包大小不一致");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(apk)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) sha.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder();
        for (byte b : sha.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!digest.equalsIgnoreCase(actual.toString())) throw new SecurityException("模块 SHA-256 校验失败");
        PackageInfo info = host.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_META_DATA | PackageManager.GET_SIGNATURES);
        if (info == null) throw new SecurityException("下载文件不是有效的模块 APK");
        RuntimeModules.verify(host, module, info);
        if (info.versionCode != version) throw new SecurityException("模块版本与目录不一致");
        try {
            if (info.versionCode < RuntimeModules.installed(host, module).versionCode) throw new SecurityException("禁止降级模块");
        } catch (PackageManager.NameNotFoundException ignored) { }
        try (ZipFile zip = new ZipFile(apk)) {
            if (zip.stream().noneMatch(entry -> entry.getName().startsWith("lib/arm64-v8a/") && entry.getName().endsWith(".so")))
                throw new SecurityException("模块缺少 ARM64 原生库");
        }
    }
}
