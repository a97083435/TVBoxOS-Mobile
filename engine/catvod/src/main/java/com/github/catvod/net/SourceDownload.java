package com.github.catvod.net;

import com.google.gson.*;
import com.github.catvod.utils.Crypto;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;

/** A partial file is reused only with an unchanged request and an origin validator. */
final class SourceDownload {
    private static final Map<String, Object> locks = new ConcurrentHashMap<>();
    static String get(Net net, String url, String path, JsonObject options) throws IOException {
        File target = new File(path).getCanonicalFile();
        synchronized (locks.computeIfAbsent(target.getPath(), key -> new Object())) {
            return transfer(net, url, target, options);
        }
    }
    private static String transfer(Net net, String url, File target, JsonObject options) throws IOException {
        int threads = options.has("threads") ? options.get("threads").getAsInt() : 0;
        if (threads < 0 || threads > 4) throw new IOException("threads 必须为 0–4");
        long expected = options.has("size") ? options.get("size").getAsLong() : -1;
        if (options.has("size") && expected <= 0) throw new IOException("size 必须为正整数");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建下载目录");
        File part = new File(target + ".part"), meta = new File(target + ".part.json");
        String identity = Crypto.md5(url + "\n" + options), validator = "";
        options = options.deepCopy();
        JsonObject headers = options.has("header") ? options.getAsJsonObject("header") : new JsonObject();
        if (!headers.has("Accept-Encoding")) headers.addProperty("Accept-Encoding", "identity");
        options.add("header", headers);
        long total = expected;
        boolean ranges = false;
        JsonObject head = options.deepCopy(); head.addProperty("method", "HEAD");
        try (Response response = net.execute(url, head)) {
            if (response.isSuccessful()) {
                long length = response.body() == null ? -1 : response.body().contentLength();
                try { length = Long.parseLong(response.header("Content-Length", "-1")); } catch (NumberFormatException ignored) {}
                if (length >= 0) {
                    if (expected > 0 && length != expected) throw new IOException("下载大小与 size 不一致");
                    total = length;
                }
                String etag = response.header("ETag", "");
                validator = !etag.isEmpty() && !etag.startsWith("W/") ? etag : response.header("Last-Modified", "");
                ranges = "bytes".equalsIgnoreCase(response.header("Accept-Ranges"));
            }
        }
        boolean simple = !options.has("method") || "GET".equalsIgnoreCase(options.get("method").getAsString());
        for (String name : List.of("header", "headers")) if (options.has(name))
            for (String key : options.getAsJsonObject(name).keySet()) if (key.equalsIgnoreCase("Range") || (key.equalsIgnoreCase("Accept-Encoding") && !"identity".equalsIgnoreCase(options.getAsJsonObject(name).get(key).getAsString()))) simple = false;
        boolean resumable = simple && ranges && total > 0 && !validator.isEmpty();
        String stamp = identity + "\n" + validator + "\n" + total;
        long offset = 0;
        if (resumable && part.isFile() && meta.isFile()) {
            String old = read(meta);
            if (stamp.equals(old) && part.length() <= total) offset = part.length();
        }
        if (offset == 0) { try (RandomAccessFile out = new RandomAccessFile(part, "rw")) { out.setLength(0); } }
        try (OutputStream out = new FileOutputStream(meta)) { out.write(stamp.getBytes(StandardCharsets.UTF_8)); }
        int parallel = threads == 0 ? 4 : threads;
        if (resumable && offset == 0 && parallel > 1 && total >= 1024 * 1024) {
            // A sparse parallel file must never be mistaken for a contiguous resume prefix.
            remove(meta);
            ExecutorService pool = Executors.newFixedThreadPool(parallel);
            List<Future<File>> tasks = new ArrayList<>();
            List<File> chunks = new ArrayList<>();
            long size = total;
            String token = validator;
            JsonObject sourceOptions = options;
            try {
                for (int i = 0; i < parallel; i++) {
                    long start = size * i / parallel, end = size * (i + 1) / parallel - 1;
                    File chunk = File.createTempFile(target.getName() + ".chunk", ".tmp", target.getParentFile()); chunks.add(chunk);
                    tasks.add(pool.submit(() -> { try { copy(net, url, chunk, range(sourceOptions, start, end, token), start, end, size, 0); return chunk; }
                        catch (Exception error) { chunk.delete(); throw error; } }));
                }
                for (Future<?> task : tasks) task.get();
                try (OutputStream out = new FileOutputStream(part)) {
                    for (File chunk : chunks) try (InputStream in = new FileInputStream(chunk)) {
                        byte[] bytes = new byte[32768]; int length; while ((length = in.read(bytes)) != -1) out.write(bytes, 0, length);
                    }
                }
            } catch (Exception error) {
                for (Future<?> task : tasks) task.cancel(true);
                Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
                if (cause instanceof RangeRefused) {
                    try (RandomAccessFile out = new RandomAccessFile(part, "rw")) { out.setLength(0); }
                    copy(net, url, part, options, 0, -1, expected, 0); total = expected;
                } else throw new IOException("分段下载失败，请重试", error);
            } finally { pool.shutdownNow(); for (File chunk : chunks) chunk.delete(); }
        } else if (offset != total || total < 0) {
            JsonObject request = offset > 0 ? range(options, offset, total - 1, validator) : options;
            try { copy(net, url, part, request, offset, offset > 0 ? total - 1 : -1, total, offset); }
            catch (RangeRefused error) {
                // If-Range may legitimately return a new complete representation.
                remove(meta);
                try (RandomAccessFile out = new RandomAccessFile(part, "rw")) { out.setLength(0); }
                copy(net, url, part, options, 0, -1, expected, 0); total = expected;
            }
        }
        if (total >= 0 && part.length() != total) throw new IOException("下载文件不完整");
        try { android.system.Os.rename(part.getPath(), target.getPath()); }
        catch (android.system.ErrnoException error) { throw new IOException("无法保存下载文件", error); }
        remove(meta);
        return target.getPath();
    }
    private static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int length;
            while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static void remove(File file) throws IOException { if (file.exists() && !file.delete()) throw new IOException("无法清理下载暂存文件"); }
    private static JsonObject range(JsonObject options, long start, long end, String validator) {
        JsonObject request = options.deepCopy();
        JsonObject headers = request.has("header") ? request.getAsJsonObject("header") : new JsonObject();
        headers.addProperty("Range", "bytes=" + start + "-" + end);
        headers.addProperty("If-Range", validator); headers.addProperty("Accept-Encoding", "identity");
        request.add("header", headers); return request;
    }
    private static final class RangeRefused extends IOException {}
    private static void copy(Net net, String url, File file, JsonObject opts, long start, long end, long total, long outputOffset) throws IOException {
        try (Response response = net.execute(url, opts)) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("下载失败：HTTP " + response.code());
            if (end >= 0) {
                String expected = "bytes " + start + "-" + end + "/" + total;
                if (response.code() != 206 || !expected.equals(response.header("Content-Range"))) throw new RangeRefused();
            } else if (response.code() != 200) throw new IOException("下载响应不完整");
            try (InputStream in = response.body().byteStream(); RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                out.seek(outputOffset); long count = 0; byte[] buffer = new byte[32768]; int length;
                while ((length = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    if (end >= 0 && count + length > end - start + 1) throw new IOException("Range 正文过长");
                    out.write(buffer, 0, length); count += length;
                }
                long required = end >= 0 ? end - start + 1 : total;
                if (required >= 0 && count != required) throw new IOException("下载正文长度不符");
            }
        }
    }
}
