package com.kukuqi.tvbox.engine.player.parse;

import android.text.TextUtils;

import com.kukuqi.tvbox.engine.App;
import com.kukuqi.tvbox.engine.Constant;
import com.kukuqi.tvbox.engine.api.config.VodConfig;
import com.kukuqi.tvbox.engine.api.loader.BaseLoader;
import com.kukuqi.tvbox.engine.bean.Parse;
import com.kukuqi.tvbox.engine.bean.Result;
import com.kukuqi.tvbox.engine.impl.ParseCallback;
import com.kukuqi.tvbox.engine.utils.Task;
import com.kukuqi.tvbox.engine.utils.UrlUtil;
import com.kukuqi.tvbox.engine.utils.WebViewUtil;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Response;

public class ParseJob implements ParseCallback {

    private final AtomicBoolean done = new AtomicBoolean();
    private final List<WebParserView> webViews;
    private ExecutorService executor;
    private ExecutorService infinite;
    private ParseCallback callback;
    private Parse parse;

    private ParseJob(ParseCallback callback) {
        this.executor = Executors.newSingleThreadExecutor();
        this.infinite = Executors.newCachedThreadPool();
        this.webViews = new ArrayList<>();
        this.callback = callback;
    }

    public static ParseJob create(ParseCallback callback) {
        return new ParseJob(callback);
    }

    public ParseJob start(Result result, boolean useParse) {
        setParse(result, useParse);
        execute(result);
        return this;
    }

    /** Host adapter: keep the selected line while using the upstream parsing pipeline. */
    public ParseJob start(Result result, Parse selected) {
        parse = withHeaders(selected, result.getHeader());
        parse.setClick(getClick(result));
        execute(result);
        return this;
    }

    private void setParse(Result result, boolean useParse) {
        if (useParse) parse = VodConfig.get().getParse();
        if (result.getPlayUrl().startsWith("json:")) parse = Parse.get(1, result.getPlayUrl().substring(5));
        if (result.getPlayUrl().startsWith("parse:")) parse = VodConfig.get().getParse(result.getPlayUrl().substring(6));
        if (parse == null || parse.isEmpty()) parse = Parse.get(0, result.getPlayUrl());
        parse = withHeaders(parse, result.getHeader());
        parse.setClick(getClick(result));
    }

    private String getClick(Result result) {
        String click = VodConfig.get().getSite(result.getKey()).getClick();
        if (!TextUtils.isEmpty(click)) return click;
        return result.getClick();
    }

    private void execute(Result result) {
        Future<?> task = executor.submit(getTask(result));
        Task.schedule(() -> {
            if (task.cancel(true)) onParseError();
        }, Constant.TIMEOUT_PARSE_DEF, TimeUnit.MILLISECONDS);
    }

    private Runnable getTask(Result result) {
        return () -> {
            try {
                doInBackground(result.getKey(), result.getUrl().v(), result.getFlag());
            } catch (Throwable e) {
                onParseError();
            }
        };
    }

    private void doInBackground(String key, String webUrl, String flag) throws Throwable {
        switch (parse.getType()) {
            case 0:
                startWeb(key, parse, webUrl);
                break;
            case 1:
                jsonParse(parse, webUrl);
                break;
            case 2:
                jsonExtend(webUrl);
                break;
            case 3:
                jsonMix(webUrl, flag);
                break;
            case 4:
                superParse(key, webUrl, flag);
                break;
            default:
                onParseError();
        }
    }

    private void jsonParse(Parse item, String webUrl) throws Exception {
        try (Response res = OkHttp.newCall(item.getUrl() + webUrl, item.getHeader()).execute()) {
            if (!res.isSuccessful() || res.body() == null) throw new java.io.IOException("解析接口请求失败：HTTP " + res.code());
            JsonParseResult result = JsonParseResult.read(res.body().string(), item.getHeader(), res.request().url().toString());
            onParseSuccess(result.headers, UrlUtil.convert(result.url), item.getName());
        }
    }

    private Parse withHeaders(Parse selected, Map<String, String> inherited) {
        Parse copy = Parse.objectFrom(App.gson().toJsonTree(selected));
        Map<String, String> headers = new LinkedHashMap<>(inherited);
        // Parser-specific headers override inherited source headers, case-insensitively.
        selected.getHeader().forEach((key, value) -> {
            headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(key));
            headers.put(key, value);
        });
        copy.getExt().setHeader(headers);
        return copy;
    }

    private void jsonExtend(String webUrl) throws Throwable {
        LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
        for (Parse item : VodConfig.get().getParses()) if (item.getType() == 1) jxs.put(item.getName(), item.extUrl());
        checkResult(Result.fromObject(BaseLoader.get().jsonExt(parse.getUrl(), jxs, webUrl)));
    }

    private void jsonMix(String webUrl, String flag) throws Throwable {
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        for (Parse item : VodConfig.get().getParses()) jxs.put(item.getName(), item.mixMap());
        checkResult(Result.fromObject(BaseLoader.get().jsonExtMix(flag, parse.getUrl(), parse.getName(), jxs, webUrl)));
    }

    private void superParse(String key, String webUrl, String flag) throws Exception {
        List<Parse> json = VodConfig.get().getParses(1, flag);
        List<Parse> webs = VodConfig.get().getParses(0, flag);
        int count = json.size() + webs.size();
        CountDownLatch latch = new CountDownLatch(count);
        for (Parse item : json) infinite.execute(() -> jsonParse(latch, withHeaders(item, parse.getHeader()), webUrl));
        for (Parse item : webs) {
            Parse line = withHeaders(item, parse.getHeader());
            startWeb(key, line.getName(), line.getHeader(), line.getUrl() + webUrl, parse.getClick(), new ParseCallback() {
                @Override public void onParseSuccess(Map<String, String> headers, String url, String from) {
                    ParseJob.this.onParseSuccess(headers, url, from);
                }
                @Override public void onParseError() { latch.countDown(); }
            });
        }
        latch.await();
        onParseError();
    }

    private void jsonParse(CountDownLatch latch, Parse item, String webUrl) {
        try {
            jsonParse(item, webUrl);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            latch.countDown();
        }
    }

    private void checkResult(Result result) {
        result.setHeader(parse.getHeader());
        if (result.getUrl().isEmpty()) onParseError();
        else if (result.needParse()) startWeb(result.getHeader(), UrlUtil.convert(result.getUrl().v()));
        else onParseSuccess(result.getHeader(), result.getUrl().v(), result.getJxFrom());
    }

    private void startWeb(String key, Parse item, String webUrl) {
        startWeb(key, item.getName(), item.getHeader(), item.getUrl() + webUrl, item.getClick());
    }

    private void startWeb(Map<String, String> headers, String url) {
        startWeb("", "", headers, url, "");
    }

    private void startWeb(String key, String from, Map<String, String> headers, String url, String click) {
        startWeb(key, from, headers, url, click, this);
    }

    private void startWeb(String key, String from, Map<String, String> headers, String url, String click, ParseCallback receiver) {
        if (!WebViewUtil.support()) {
            receiver.onParseError();
        } else {
            App.post(() -> {
                if (done.get()) return;
                try {
                    webViews.add(WebParserView.create(App.get()).start(key, from, headers, url, click, receiver, !url.contains("player/?url=")));
                } catch (RuntimeException failure) {
                    receiver.onParseError();
                }
            });
        }
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!done.compareAndSet(false, true)) return;
        App.post(() -> {
            if (callback != null) callback.onParseSuccess(headers, url, from);
            stop();
        });
    }

    @Override
    public void onParseError() {
        if (!done.compareAndSet(false, true)) return;
        App.post(() -> {
            if (callback != null) callback.onParseError();
            stop();
        });
    }

    private void stopWeb() {
        for (WebParserView webView : webViews) webView.stop(false);
        for (WebParserView webView : webViews) webView.destroy();
        if (!webViews.isEmpty()) webViews.clear();
    }

    public void stop() {
        if (executor != null) executor.shutdownNow();
        if (infinite != null) infinite.shutdownNow();
        infinite = null;
        executor = null;
        callback = null;
        done.set(true);
        stopWeb();
    }
}
