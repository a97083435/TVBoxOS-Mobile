package com.github.catvod.net;

import android.webkit.CookieManager;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Prefers;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.*;

/** Per-source network lifetime shared by Java and scripting bridges. Times are milliseconds. */
public final class Net implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    public interface Loader { String load() throws Exception; }
    public interface SocketCallback { void event(Socket socket, SocketEvent event) throws Exception; }
    public static final class SocketEvent {
        public final String type;
        public final Object content;
        SocketEvent(String type, Object content) { this.type = type; this.content = content; }
        public String toJson() {
            JsonObject value = new JsonObject(); value.addProperty("type", type);
            if (content instanceof byte[] bytes) value.addProperty("binary", okio.ByteString.of(bytes).base64());
            else value.addProperty("content", String.valueOf(content));
            return value.toString();
        }
    }
    private record Entry(String text, long expires) {}
    private final String scope;
    private final boolean isolated;
    private final Map<String, List<Cookie>> cookies = new ConcurrentHashMap<>();
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<Net> sessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Future<String>> loading = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();
    private final Map<String, Long> generations = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final ExecutorService loaders = Executors.newFixedThreadPool(4);
    private volatile boolean closed;

    public Net(String scope) { this(scope, false); }
    private Net(String scope, boolean isolated) { this.scope = scope; this.isolated = isolated; }
    public Net session() { check(); Net child = new Net(scope + ":session:" + UUID.randomUUID(), true); sessions.add(child); return child; }
    private void check() { if (closed) throw new IllegalStateException("来源网络已关闭"); }
    private static JsonObject options(String text) {
        if (text == null || text.isEmpty()) return new JsonObject();
        return JsonParser.parseString(text).getAsJsonObject();
    }
    private OkHttpClient client(JsonObject opts) {
        check();
        long timeout = Math.max(1, opts.has("timeout") ? opts.get("timeout").getAsLong() : 30000);
        OkHttpClient.Builder builder = OkHttp.client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout, TimeUnit.MILLISECONDS);
        if (opts.has("redirect")) builder.followRedirects(opts.get("redirect").getAsBoolean()).followSslRedirects(opts.get("redirect").getAsBoolean());
        if (isolated || (opts.has("cookie") && opts.get("cookie").getAsBoolean())) builder.cookieJar(new CookieJar() {
            public List<Cookie> loadForRequest(HttpUrl url) {
                if (!isolated) {
                    String text = getCookie(url.toString()); List<Cookie> values = new ArrayList<>();
                    for (String part : text.split(";")) { Cookie cookie = Cookie.parse(url, part.trim()); if (cookie != null) values.add(cookie); }
                    return values;
                }
                List<Cookie> values = new ArrayList<>();
                for (List<Cookie> list : cookies.values()) for (Cookie cookie : list)
                    if (cookie.expiresAt() > System.currentTimeMillis() && cookie.matches(url)) values.add(cookie);
                return values;
            }
            public void saveFromResponse(HttpUrl url, List<Cookie> incoming) {
                if (!isolated) { for (Cookie cookie : incoming) setCookie(url.toString(), cookie.toString()); return; }
                for (Cookie cookie : incoming) cookies.put(cookie.domain() + "|" + cookie.path() + "|" + cookie.name(), List.of(cookie));
            }
        });
        return builder.build();
    }
    private Request request(String url, JsonObject opts) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) throw new IllegalArgumentException("HTTP 地址无效");
        HttpUrl.Builder address = parsed.newBuilder();
        if (opts.has("params")) for (var entry : opts.getAsJsonObject("params").entrySet())
            address.addQueryParameter(entry.getKey(), entry.getValue().getAsString());
        Request.Builder request = new Request.Builder().url(address.build());
        for (String key : List.of("headers", "header")) if (opts.has(key) && opts.get(key).isJsonObject())
            for (var entry : opts.getAsJsonObject(key).entrySet()) request.header(entry.getKey(), entry.getValue().getAsString());
        String method = opts.has("method") ? opts.get("method").getAsString().toUpperCase(Locale.ROOT) : "GET";
        RequestBody body = null;
        if (!method.equals("GET") && !method.equals("HEAD")) {
            String content = "";
            if (opts.has("body")) content = opts.get("body").isJsonPrimitive() ? opts.get("body").getAsString() : opts.get("body").toString();
            String type = request.build().header("Content-Type");
            body = RequestBody.create(MediaType.parse(type == null ? "application/json; charset=utf-8" : type), content);
        }
        return request.method(method, body).build();
    }
    Response execute(String url, JsonObject opts) throws IOException {
        Call call = client(opts).newCall(request(url, opts)); calls.add(call);
        if (closed) { calls.remove(call); call.cancel(); throw new IOException("来源网络已关闭"); }
        try {
            Response response = call.execute(); ResponseBody body = response.body();
            if (body == null) { calls.remove(call); return response; }
            okio.BufferedSource source = okio.Okio.buffer(new okio.ForwardingSource(body.source()) {
                @Override public void close() throws IOException { try { super.close(); } finally { calls.remove(call); } }
            });
            return response.newBuilder().body(new ResponseBody() {
                public MediaType contentType() { return body.contentType(); }
                public long contentLength() { return body.contentLength(); }
                public okio.BufferedSource source() { return source; }
            }).build();
        } catch (IOException | RuntimeException error) { calls.remove(call); throw error; }
    }
    /** Network errors are data; HTTP error bodies remain available. */
    public String req(String url) { return req(url, "{}"); }
    public String req(String url, String text) {
        JsonObject result = new JsonObject();
        try (Response response = execute(url, options(text))) {
            result.addProperty("code", response.code()); result.addProperty("url", response.request().url().toString());
            JsonObject headers = new JsonObject();
            for (String name : response.headers().names()) headers.addProperty(name, response.header(name));
            result.add("headers", headers); result.addProperty("content", response.body() == null ? "" : response.body().string());
            result.addProperty("error", "");
        } catch (Exception error) {
            result.addProperty("code", 0); result.addProperty("content", ""); result.add("headers", new JsonObject());
            result.addProperty("error", error.toString());
        }
        return result.toString();
    }
    public String http(String url, String opts) { return req(url, opts); }
    public String http(String url) { return req(url); }
    public String json(String url) throws IOException { return json(url, "{}"); }
    public String json(String url, String opts) throws IOException {
        JsonObject response = JsonParser.parseString(req(url, opts)).getAsJsonObject();
        int code = response.get("code").getAsInt();
        if (code < 200 || code >= 300) throw new IOException("请求失败：HTTP " + code + " " + response.get("error").getAsString());
        try {
            String content = response.get("content").getAsString();
            if (content.trim().isEmpty()) throw new IllegalArgumentException("JSON 正文为空");
            JsonElement value = JSON.fromJson(content, JsonElement.class); return value == null ? "null" : value.toString();
        }
        catch (RuntimeException error) { throw new IOException("响应不是有效 JSON", error); }
    }
    public String get(String url, String opts) throws IOException {
        try (Response response = execute(url, options(opts))) { return response.body() == null ? "" : response.body().string(); }
    }
    public String get(String url) throws IOException { return get(url, "{}"); }
    public String post(String url, String opts) throws IOException {
        JsonObject data = options(opts); data.addProperty("method", "POST"); return get(url, data.toString());
    }
    public String post(String url) throws IOException { return post(url, "{}"); }
    public String getCookie(String url) {
        if (isolated) {
            HttpUrl address = HttpUrl.parse(url); if (address == null) return "";
            List<String> values = new ArrayList<>();
            for (List<Cookie> list : cookies.values()) for (Cookie cookie : list)
                if (cookie.expiresAt() > System.currentTimeMillis() && cookie.matches(address)) values.add(cookie.name() + "=" + cookie.value());
            return String.join("; ", values);
        }
        String value = CookieManager.getInstance().getCookie(url); return value == null ? "" : value;
    }
    public void setCookie(String url, String value) {
        check();
        if (isolated) { HttpUrl address = HttpUrl.parse(url); Cookie cookie = address == null ? null : Cookie.parse(address, value);
            if (cookie != null) cookies.put(cookie.domain() + "|" + cookie.path() + "|" + cookie.name(), List.of(cookie));
        } else CookieManager.getInstance().setCookie(url, value);
    }
    public String batch(String text) throws Exception {
        check(); JsonArray requests = JsonParser.parseString(text).getAsJsonArray();
        List<Future<String>> pending = new ArrayList<>();
        for (JsonElement item : requests) pending.add(workers.submit(() -> {
            if (item.isJsonPrimitive()) return req(item.getAsString());
            JsonObject request = item.getAsJsonObject();
            return req(request.get("url").getAsString(), request.has("options") ? request.get("options").toString() : request.toString());
        }));
        JsonArray result = new JsonArray();
        try { for (Future<String> task : pending) result.add(JsonParser.parseString(task.get())); }
        finally { for (Future<String> task : pending) task.cancel(true); }
        return result.toString();
    }
    private String persistentKey(String key) { return "source_net_" + Crypto.md5(scope) + "_" + Crypto.md5(key); }
    private Entry entry(String key) {
        Entry value = cache.get(key); if (value != null || isolated) return value;
        try { JsonObject data = options(Prefers.getString(persistentKey(key), "{}"));
            if (data.has("value")) { value = new Entry(data.get("value").getAsString(), data.get("expires").getAsLong()); cache.put(key, value); }
        } catch (RuntimeException ignored) {}
        return value;
    }
    public String peek(String key) { Entry value = entry(key); return value == null ? null : value.text; }
    public String cached(String key, String text, Loader loader) throws Exception {
        check(); JsonObject opts = options(text); Entry value = entry(key);
        if (value != null && value.expires > System.currentTimeMillis()) return value.text;
        if (value != null && opts.has("stale") && opts.get("stale").getAsBoolean()) { refresh(key, text, loader); return value.text; }
        return load(key, opts, loader);
    }
    private synchronized Future<String> task(String key, JsonObject opts, Loader loader) {
        check(); Future<String> existing = loading.get(key); if (existing != null && !existing.isDone()) return existing;
        long generation = generations.getOrDefault(key, 0L), all = epoch.get();
        Future<String> future = loaders.submit(() -> {
            String value = loader.load(); if (value == null) return null;
            // Clear/close invalidates already running loaders too.
            synchronized (Net.this) {
                if (closed || epoch.get() != all || generation != generations.getOrDefault(key, 0L)) return value;
                long ttl = Math.max(0, opts.has("ttl") ? opts.get("ttl").getAsLong() : 60000);
                Entry ready = new Entry(value, System.currentTimeMillis() + ttl); cache.put(key, ready);
                if (!isolated && opts.has("persist") && opts.get("persist").getAsBoolean()) {
                    JsonObject data = new JsonObject(); data.addProperty("value", value); data.addProperty("expires", ready.expires);
                    Prefers.put(persistentKey(key), data.toString());
                }
            }
            return value;
        });
        loading.put(key, future); return future;
    }
    private String load(String key, JsonObject opts, Loader loader) throws Exception {
        try { return task(key, opts, loader).get(); }
        catch (ExecutionException error) { if (error.getCause() instanceof Exception cause) throw cause; throw error; }
    }
    public void refresh(String key, String opts, Loader loader) {
        check(); Entry value = entry(key);
        if (value == null || value.expires <= System.currentTimeMillis()) task(key, options(opts), loader);
    }
    public synchronized void clearCache(String key) {
        generations.merge(key, 1L, Long::sum); cache.remove(key);
        Future<String> future = loading.remove(key); if (future != null) future.cancel(true);
        if (!isolated) Prefers.remove(persistentKey(key));
    }
    public synchronized void clearCache() {
        epoch.incrementAndGet(); for (Future<?> future : loading.values()) future.cancel(true); loading.clear(); cache.clear();
        String prefix = "source_net_" + Crypto.md5(scope) + "_";
        if (!isolated) for (String key : Prefers.getPrefers().getAll().keySet()) if (key.startsWith(prefix)) Prefers.remove(key);
    }
    public String download(String url, String file, String opts) throws IOException {
        check(); return SourceDownload.get(this, url, file, options(opts));
    }
    public void sleep(long milliseconds) throws InterruptedException {
        long until = android.os.SystemClock.elapsedRealtime() + Math.max(0, milliseconds);
        while (android.os.SystemClock.elapsedRealtime() < until) { check(); Thread.sleep(Math.max(1, Math.min(100, until - android.os.SystemClock.elapsedRealtime()))); }
    }
    public final class Socket implements AutoCloseable {
        private volatile WebSocket connection;
        private volatile boolean stopped;
        public boolean send(String text) { return !stopped && connection != null && connection.send(text); }
        public boolean send(byte[] bytes) { return !stopped && connection != null && connection.send(okio.ByteString.of(bytes)); }
        public void close() { stopped = true; sockets.remove(this); if (connection != null) connection.cancel(); }
    }
    public Socket connect(String url, String text, SocketCallback callback) {
        JsonObject opts = options(text); Socket socket = new Socket(); sockets.add(socket);
        // OkHttp removes the stream read timeout after a successful WebSocket upgrade.
        OkHttpClient.Builder builder = client(opts).newBuilder().callTimeout(0, TimeUnit.MILLISECONDS);
        if (opts.has("ping")) builder.pingInterval(Math.max(0, opts.get("ping").getAsLong()), TimeUnit.MILLISECONDS);
        socket.connection = builder.build().newWebSocket(request(url.replaceFirst("^ws:", "http:").replaceFirst("^wss:", "https:"), opts), new WebSocketListener() {
            private void emit(String event, Object content) {
                try { callback.event(socket, new SocketEvent(event, content)); } catch (Exception ignored) { socket.close(); }
            }
            @Override public void onOpen(WebSocket ws, Response response) { socket.connection = ws; if (closed || socket.stopped) { ws.cancel(); emit("close", ""); } else emit("open", ""); }
            @Override public void onMessage(WebSocket ws, String text) { emit("message", text); }
            @Override public void onMessage(WebSocket ws, okio.ByteString bytes) {
                emit("message", bytes.toByteArray());
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) { sockets.remove(socket); if (response != null) response.close(); emit("error", error.toString()); }
            @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
            @Override public void onClosed(WebSocket ws, int code, String reason) { sockets.remove(socket); emit("close", reason); }
        });
        if (closed || socket.stopped) socket.connection.cancel();
        return socket;
    }
    public String ws(String url, String opts) throws Exception {
        CountDownLatch done = new CountDownLatch(1); String[] result = new String[1];
        Socket socket = connect(url, opts, (connection, event) -> {
            String type = event.type;
            if (type.equals("open") && options(opts).has("body")) connection.send(options(opts).get("body").getAsString());
            if (type.equals("message") || type.equals("error") || type.equals("close")) { synchronized (result) { if (result[0] == null) result[0] = event.toJson(); } done.countDown(); }
        });
        try {
            if (!done.await(Math.max(1, options(opts).has("timeout") ? options(opts).get("timeout").getAsLong() : 30000), TimeUnit.MILLISECONDS)) throw new IOException("WebSocket 超时");
            if (!"message".equals(options(result[0]).get("type").getAsString())) throw new IOException("WebSocket 未收到消息：" + result[0]);
            return result[0];
        }
        finally { socket.close(); }
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true; epoch.incrementAndGet();
        for (Call call : calls) call.cancel(); calls.clear();
        for (Socket socket : sockets) socket.close(); sockets.clear();
        for (Net child : sessions) child.close(); sessions.clear();
        for (Future<?> future : loading.values()) future.cancel(true); loading.clear(); cache.clear(); cookies.clear(); workers.shutdownNow(); loaders.shutdownNow();
    }
}
