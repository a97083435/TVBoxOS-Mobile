package com.kukuqi.tvbox.engine.api.loader;

import android.content.*;
import android.os.*;
import com.github.catvod.crawler.Spider;
import com.kukuqi.tvbox.engine.App;
import com.kukuqi.tvbox.engine.runtime.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import okhttp3.*;

/** Node sources use nodejs:<URL>, node:<URL>, .cjs or .mjs; ordinary .js stays QuickJS. */
public final class NodeLoader {
    private final Map<String, Spider> spiders = new ConcurrentHashMap<>();
    private static final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();
    private ServiceConnection connection;
    private volatile String[] endpoint;
    private String recent;
    public static boolean accepts(String api) {
        if (api.startsWith("nodejs:") || api.startsWith("node:")) return true;
        String path = api.split("[?#]", 2)[0].toLowerCase(java.util.Locale.ROOT);
        return path.endsWith(".cjs") || path.endsWith(".mjs");
    }
    private synchronized void connect() throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Node.js 需要在后台加载");
        // Record the loaded APK identity in the host too, so updates require a clear restart.
        RuntimeModules.loader(App.get(), RuntimeModule.NODEJS);
        if (endpoint != null) return;
        if (connection != null) { App.get().unbindService(connection); connection = null; }
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            endpoint = message.getData().getStringArray("endpoint"); error.set(message.getData().getString("error")); ready.countDown(); return true;
        }));
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Message message = Message.obtain(null, 1); message.replyTo = receiver;
                try { new Messenger(binder).send(message); } catch (RemoteException failure) { error.set("Node.js 连接断开"); ready.countDown(); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { endpoint = null; error.set("Node.js 进程已退出"); ready.countDown(); }
            @Override public void onNullBinding(ComponentName name) { error.set("Node.js 服务不可用"); ready.countDown(); }
        };
        if (!App.get().bindService(new Intent(App.get(), NodeService.class), connection, Context.BIND_AUTO_CREATE)) {
            connection = null; throw new IllegalStateException("无法启动 Node.js 服务");
        }
        if (!ready.await(20, TimeUnit.SECONDS) || endpoint == null) {
            clear(); throw new IllegalStateException(error.get() == null ? "Node.js 启动超时" : error.get());
        }
        Exception last = null;
        for (int i = 0; i < 100; i++) {
            try { rpc("", "ping"); return; }
            catch (java.io.IOException failure) { last = failure; Thread.sleep(100); }
        }
        clear(); throw new IllegalStateException("Node.js 运行时未就绪", last);
    }
    public synchronized Spider getSpider(String key, String api, String ext) {
        try {
            connect();
            Spider current = spiders.get(key); if (current != null) return current;
            String location = api.startsWith("nodejs:") ? api.substring(7) : api.startsWith("node:") ? api.substring(5) : api;
            rpc(key, "load", location, ext);
            Spider spider = new NodeSpider(this, key); spiders.put(key, spider); return spider;
        } catch (Exception failure) { throw new IllegalStateException("Node.js 来源加载失败：" + failure.getMessage(), failure); }
    }
    Object rpc(String key, String method, Object... args) throws Exception {
        String[] address = endpoint;
        if (address == null) throw new IllegalStateException("Node.js 进程已退出，请重新加载来源");
        JSONArray array = new JSONArray(); for (Object arg : args) array.put(JSONObject.wrap(arg));
        String json = new JSONObject().put("key", key).put("method", method).put("args", array).toString();
        Request request = new Request.Builder().url(address[0] + "/rpc").header("Authorization", "Bearer " + address[1])
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json)).build();
        try (Response response = client.newCall(request).execute()) {
            if (response.body() == null) throw new java.io.IOException("Node.js 响应为空");
            JSONObject result = new JSONObject(response.body().string());
            if (!response.isSuccessful() || result.has("error")) throw new IllegalStateException(result.optString("error", "Node.js 调用失败"));
            return result.opt("result");
        }
    }
    public void setRecent(String key) { recent = key; }
    public Object[] proxy(Map<String,String> params) throws Exception { Spider spider = spiders.get(recent == null ? "" : recent); return spider == null ? null : spider.proxy(params); }
    public synchronized void clear() {
        spiders.clear(); recent = null; endpoint = null;
        if (connection != null) { App.get().unbindService(connection); connection = null; }
    }
}
