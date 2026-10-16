package com.kukuqi.tvbox.osc.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Routing and resumable transfer for public release assets. Verification stays in RuntimeDownload. */
public final class RuntimeTransfer {
    public static final String PRIMARY = "https://gh-proxy.com/";
    public static final String SECONDARY = "https://ghfast.top/";
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private final OkHttpClient client;

    RuntimeTransfer(OkHttpClient client) { this.client = client; }

    public static List<String> routes(String url, int mode) {
        String canonical = url;
        for (String prefix : List.of(PRIMARY, SECONDARY)) {
            if (url.startsWith(prefix)) { canonical = url.substring(prefix.length()); break; }
        }
        // Private/custom catalogs, credentials and query tokens must never be sent to a public proxy.
        try {
            URI uri = URI.create(canonical);
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || path == null || !path.startsWith("/kukuqi666/TVboxOSC/releases/")
                    || !(path.startsWith("/kukuqi666/TVboxOSC/releases/download/")
                    || path.startsWith("/kukuqi666/TVboxOSC/releases/latest/download/"))) return List.of(url);
        } catch (IllegalArgumentException failure) { return List.of(url); }
        return switch (mode) {
            case 1 -> List.of(canonical);
            case 2 -> List.of(PRIMARY + canonical, canonical);
            case 3 -> List.of(SECONDARY + canonical, canonical);
            default -> List.of(PRIMARY + canonical, SECONDARY + canonical, canonical);
        };
    }

    public static String routeName(String url) {
        if (url.startsWith(PRIMARY)) return "加速线路 1";
        if (url.startsWith(SECONDARY)) return "加速线路 2";
        return "直连";
    }

    Response request(String url) throws IOException {
        Response response = client.newCall(new Request.Builder().url(url).header("User-Agent", "TVboxOSC")
                .header("Accept-Encoding", "identity").build()).execute();
        if (!response.isSuccessful() || response.body() == null) {
            int code = response.code(); response.close(); throw new IOException("下载失败 HTTP " + code);
        }
        return response;
    }

    void download(String url, File part, long size, Consumer<String> progress) throws IOException {
        long offset = part.isFile() ? part.length() : 0;
        if (offset > size) { if (!part.delete()) throw new IOException("无法重置下载缓存"); offset = 0; }
        if (offset == size) return; // Still requires SHA-256, APK and signer verification by the caller.
        Request.Builder request = new Request.Builder().url(url).header("User-Agent", "TVboxOSC")
                .header("Accept-Encoding", "identity");
        if (offset > 0) request.header("Range", "bytes=" + offset + "-");
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("下载失败 HTTP " + response.code());
            if (response.code() == 206) {
                Matcher range = RANGE.matcher(response.header("Content-Range", ""));
                if (!range.matches() || Long.parseLong(range.group(1)) != offset
                        || Long.parseLong(range.group(2)) < offset || Long.parseLong(range.group(2)) >= size
                        || Long.parseLong(range.group(3)) != size) throw new IOException("模块续传范围无效");
            } else if (response.code() == 200) {
                offset = 0; // Servers without Range support return the entire file. Never append that response.
            } else throw new IOException("模块下载响应无效 HTTP " + response.code());
            if (response.body().contentLength() > size - offset) throw new IOException("模块长度超出目录声明");
            try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(part, offset > 0)) {
                long total = offset, last = System.nanoTime(), lastBytes = total;
                byte[] buffer = new byte[32768]; int count;
                progress.accept(routeName(url) + " · " + (offset > 0 ? "正在续传 " : "正在下载 ") + total * 100 / size + "%");
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
                    total += count;
                    if (total > size) throw new IOException("模块长度超出目录声明");
                    out.write(buffer, 0, count);
                    long now = System.nanoTime();
                    if (now - last >= 500_000_000L) {
                        double kib = (total - lastBytes) * 1_000_000_000.0 / (now - last) / 1024;
                        progress.accept(String.format(Locale.ROOT, "%s · %d%% · %.0f KB/s", routeName(url), total * 100 / size, kib));
                        last = now; lastBytes = total;
                    }
                }
                if (total != size) throw new IOException("模块下载不完整");
                out.getFD().sync();
            }
        }
    }
}
