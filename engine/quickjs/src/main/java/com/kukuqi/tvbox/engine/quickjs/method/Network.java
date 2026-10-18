package com.kukuqi.tvbox.engine.quickjs.method;

import com.github.catvod.net.Net;
import com.github.catvod.Local;
import com.google.gson.*;
import com.whl.quickjs.wrapper.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** No JS values are accessed from an IO or WebSocket thread. */
public final class Network implements AutoCloseable {
    private final QuickJSContext ctx;
    private final ExecutorService js;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Map<String, Net> networks = new ConcurrentHashMap<>();
    private final Map<String, Net.Socket> sockets = new ConcurrentHashMap<>();
    private final Set<JSFunction> callbacks = ConcurrentHashMap.newKeySet();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile boolean closed;
    public Network(QuickJSContext ctx, ExecutorService js, Net net, Local local) {
        this.ctx = ctx; this.js = js; networks.put("root", net);
        ctx.getGlobalObject().setProperty("_sourceLocal", args -> {
            String method = (String) args[0], key = (String) args[1];
            if (method.equals("get")) return local.get(key);
            if (method.equals("set")) local.set(key, (String) args[2]); else local.delete(key);
            return null;
        });
        ctx.getGlobalObject().setProperty("_sourceNet", args -> {
            String id = (String) args[0], method = (String) args[1], data = (String) args[2];
            JSFunction callback = args.length > 3 && args[3] instanceof JSFunction function ? function : null;
            if (callback == null) return invoke(id, method, data);
            callback.hold(); callbacks.add(callback);
            if (method.equals("connect")) { connect(id, data, callback); return null; }
            io.submit(() -> { String result = invoke(id, method, data); post(callback, result, true); });
            return null;
        });
    }
    private String invoke(String id, String method, String data) {
        JsonObject result = new JsonObject();
        try {
            if (closed) throw new IllegalStateException("来源网络已关闭");
            JsonArray args = JsonParser.parseString(data).getAsJsonArray();
            String first = args.isEmpty() || !args.get(0).isJsonPrimitive() ? "" : args.get(0).getAsString();
            String opts = args.size() > 1 ? args.get(1).toString() : "{}";
            String value;
            Net net = networks.get(id);
            if (method.equals("send")) {
                Net.Socket socket = sockets.get(id);
                boolean sent;
                if (args.get(0).isJsonArray()) { JsonArray array = args.get(0).getAsJsonArray(); byte[] bytes = new byte[array.size()];
                    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) array.get(i).getAsInt(); sent = socket != null && socket.send(bytes);
                } else sent = socket != null && socket.send(first);
                value = String.valueOf(sent);
            } else if (method.equals("socketClose")) { Net.Socket socket = sockets.remove(id); if (socket != null) socket.close(); value = "null";
            } else {
                if (net == null) throw new IllegalStateException("Session 已关闭");
                value = switch (method) {
                    case "req", "http" -> net.req(first, opts);
                    case "json" -> net.json(first, opts);
                    case "get" -> net.get(first, opts);
                    case "post" -> net.post(first, opts);
                    case "batch" -> net.batch(args.get(0).toString());
                    case "getCookie" -> net.getCookie(first);
                    case "setCookie" -> { net.setCookie(first, args.get(1).getAsString()); yield "null"; }
                    case "download" -> net.download(first, args.get(1).getAsString(), args.size() > 2 ? args.get(2).toString() : "{}");
                    case "ws" -> net.ws(first, opts);
                    case "sleep" -> { net.sleep(args.get(0).getAsLong()); yield "null"; }
                    case "session" -> { String key = "session" + ids.incrementAndGet(); networks.put(key, net.session()); yield key; }
                    case "close" -> { networks.remove(id); net.close(); yield "null"; }
                    default -> throw new IllegalArgumentException("未知网络方法 " + method);
                };
            }
            result.addProperty("ok", true); result.addProperty("value", value);
        } catch (Exception error) { result.addProperty("ok", false); result.addProperty("error", error.toString()); }
        return result.toString();
    }
    private void connect(String id, String data, JSFunction callback) {
        String key = "socket" + ids.incrementAndGet();
        try {
            JsonArray args = JsonParser.parseString(data).getAsJsonArray();
            Net net = networks.get(id); if (net == null) throw new IllegalStateException("Session 已关闭");
            Net.Socket socket = net.connect(args.get(0).getAsString(), args.size() > 1 ? args.get(1).toString() : "{}", (connection, event) -> {
                JsonObject value = JsonParser.parseString(event.toJson()).getAsJsonObject(); value.addProperty("socket", key);
                String type = value.get("type").getAsString(); boolean terminal = type.equals("close") || type.equals("error");
                if (terminal) sockets.remove(key); else sockets.put(key, connection);
                post(callback, value.toString(), terminal);
            });
            // Events register the socket before JS is notified; never resurrect a failed connection.
        } catch (Exception error) {
            JsonObject value = new JsonObject(); value.addProperty("socket", key); value.addProperty("type", "error"); value.addProperty("content", error.toString());
            post(callback, value.toString(), true);
        }
    }
    private void post(JSFunction callback, String value, boolean release) {
        if (closed) return;
        try { js.submit(() -> {
            if (closed || !callbacks.contains(callback)) return;
            try { callback.call(value); } finally { if (release && callbacks.remove(callback)) callback.release(); }
        }); } catch (RejectedExecutionException ignored) {}
    }
    @Override public void close() {
        closed = true;
        for (Net net : networks.values()) net.close(); networks.clear(); sockets.clear(); io.shutdownNow();
        for (JSFunction callback : callbacks) callback.release(); callbacks.clear();
    }
}
