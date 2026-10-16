package com.kukuqi.tvbox.osc.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import com.kukuqi.tvbox.engine.runtime.RuntimeModule;
import com.kukuqi.tvbox.engine.runtime.RuntimeModules;
import com.kukuqi.tvbox.osc.BuildConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Download to private staging storage; verify before offering the APK to Android's installer. */
public final class RuntimeDownload {
    public static final String CATALOG_KEY = "runtime_catalog_url";
    private static final long LIMIT = 160L * 1024 * 1024;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).followSslRedirects(false).build();
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
    public static String errorMessage(Exception failure) {
        if (failure instanceof java.net.ConnectException) return "无法连接模块服务器，请检查下载地址";
        if (failure instanceof java.net.UnknownHostException) return "无法解析模块服务器，请检查网络";
        if (failure instanceof java.net.SocketTimeoutException) return "连接超时，请重试";
        if (failure instanceof javax.net.ssl.SSLException) return "模块服务器安全连接失败";
        return failure.getMessage() == null ? "下载失败，请重试" : failure.getMessage();
    }
    private static void checkUrl(String url) {
        if (url == null || !(url.startsWith("https://") || (BuildConfig.DEBUG && url.startsWith("http://"))))
            throw new IllegalArgumentException("模块下载地址须使用 HTTPS");
    }
    private static Response request(String url) throws Exception {
        checkUrl(url);
        Response response = CLIENT.newCall(new Request.Builder().url(url).build()).execute();
        if (!response.isSuccessful() || response.body() == null) {
            int code = response.code(); response.close(); throw new java.io.IOException("下载失败 HTTP " + code);
        }
        checkUrl(response.request().url().toString());
        return response;
    }
    @SuppressWarnings("deprecation")
    public static synchronized File download(Context host, RuntimeModule module, String catalogUrl, Consumer<String> progress) throws Exception {
        if (catalogUrl == null || catalogUrl.trim().isEmpty()) throw new IllegalStateException("尚未设置模块下载地址");
        progress.accept("正在读取模块目录…");
        JSONObject catalog;
        try (Response response = request(catalogUrl.trim()); InputStream in = response.body().byteStream()) {
            byte[] data = in.readNBytes(1024 * 1024 + 1);
            if (data.length > 1024 * 1024) throw new java.io.IOException("模块目录过大");
            catalog = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            if ("下载失败 HTTP 404".equals(failure.getMessage()))
                throw new java.io.IOException("模块暂未上架，请稍后重试或检查下载地址", failure);
            throw failure;
        }
        if (catalog.getInt("schema") != 1) throw new IllegalArgumentException("不支持的模块目录版本");
        JSONArray modules = catalog.getJSONArray("modules");
        JSONObject selected = null, artifact = null;
        for (int i = 0; i < modules.length(); i++) {
            JSONObject item = modules.getJSONObject(i);
            if (module.id.equals(item.getString("id"))) { selected = item; break; }
        }
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
        File directory = new File(host.getCacheDir(), "runtime-downloads");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("无法创建下载目录");
        File part = new File(directory, module.id + ".part");
        File verified = new File(directory, module.id + ".apk");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (Response response = request(artifact.getString("url")); InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(part)) {
                long total = 0, last = 0; byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("下载已取消");
                    total += count;
                    if (total > size) throw new java.io.IOException("模块长度超出目录声明");
                    out.write(buffer, 0, count); sha.update(buffer, 0, count);
                    if (total - last >= 512 * 1024) { last = total; progress.accept("正在下载 " + (total * 100 / size) + "%"); }
                }
                if (total != size) throw new java.io.IOException("模块下载不完整");
                out.getFD().sync();
            }
            StringBuilder actual = new StringBuilder();
            for (byte b : sha.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (!digest.equalsIgnoreCase(actual.toString())) throw new SecurityException("模块 SHA-256 校验失败");
            PackageInfo info = host.getPackageManager().getPackageArchiveInfo(part.getAbsolutePath(), PackageManager.GET_META_DATA | PackageManager.GET_SIGNATURES);
            if (info == null) throw new SecurityException("下载文件不是有效的模块 APK");
            RuntimeModules.verify(host, module, info);
            if (info.versionCode != selected.getInt("versionCode")) throw new SecurityException("模块版本与目录不一致");
            try {
                if (info.versionCode < RuntimeModules.installed(host, module).versionCode) throw new SecurityException("禁止降级模块");
            } catch (PackageManager.NameNotFoundException ignored) { }
            try (ZipFile zip = new ZipFile(part)) {
                if (zip.stream().noneMatch(entry -> entry.getName().startsWith("lib/arm64-v8a/") && entry.getName().endsWith(".so")))
                    throw new SecurityException("模块缺少 ARM64 原生库");
            }
            if (verified.exists() && !verified.delete()) throw new java.io.IOException("无法替换模块安装包");
            if (!part.renameTo(verified)) throw new java.io.IOException("无法完成模块下载");
            progress.accept("校验通过，等待安装");
            return verified;
        } finally { if (part.exists()) part.delete(); }
    }
}
