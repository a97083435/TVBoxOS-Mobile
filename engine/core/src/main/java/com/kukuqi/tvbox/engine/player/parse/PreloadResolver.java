package com.kukuqi.tvbox.engine.player.parse;

import com.kukuqi.tvbox.engine.App;
import com.kukuqi.tvbox.engine.bean.Parse;
import com.kukuqi.tvbox.engine.bean.Result;
import com.kukuqi.tvbox.engine.player.extractor.Source;
import com.github.catvod.net.OkHttp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import java.io.IOException;

/** Background-only resolution. Never starts a WebView, script loader or shared extractor. */
public final class PreloadResolver {
    private PreloadResolver() {}

    public static Result resolve(Result input, Parse selected, List<Parse> fallback) throws Exception {
        if (input == null || input.hasMsg() || input.getDrm() != null || Thread.currentThread().isInterrupted()) return null;
        Result result = Result.objectFrom(input.toString());
        if (!result.needParse()) {
            if (Source.get().requiresExtraction(result)) return null;
            result.setUrl(result.getRealUrl());
            result.setPlayUrl("");
            return isHttp(result.getUrl().v()) ? result : null;
        }
        Parse parser = selected;
        String prefix = result.getPlayUrl();
        if (prefix.startsWith("json:")) parser = Parse.get(1, prefix.substring(5));
        else if (prefix.startsWith("parse:")) {
            String name = prefix.substring(6);
            parser = fallback.stream().filter(item -> item.getName().equals(name)).findFirst().orElse(null);
        } else if (!prefix.isEmpty() || !result.isUseParse()) return null;
        if (parser == null) return null;
        List<Parse> candidates = List.of(parser);
        if (parser.getType() == 4) {
            List<Parse> json = fallback.stream().filter(item -> item.getType() == 1).toList();
            String flag = result.getFlag();
            List<Parse> matching = json.stream().filter(item -> item.getExt().getFlag().contains(flag)).toList();
            candidates = matching.isEmpty() ? json : matching;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int attempts = 0;
        for (Parse item : candidates) {
            if (item.getType() != 1 || !isHttp(item.getUrl())) continue;
            if (++attempts > 4) return null;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            long timeout = candidates.size() > 1 ? Math.min(remaining, TimeUnit.SECONDS.toNanos(3)) : remaining;
            Map<String, String> headers = new LinkedHashMap<>(result.getHeader());
            item.getHeader().forEach((key, value) -> {
                headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(key));
                headers.put(key, value);
            });
            Call call = OkHttp.newCall(item.getUrl() + result.getUrl().v(), headers);
            call.timeout().timeout(timeout, TimeUnit.NANOSECONDS);
            CompletableFuture<JsonParseResult> response = new CompletableFuture<>();
            call.enqueue(new Callback() {
                @Override public void onFailure(Call ignored, IOException failure) { response.completeExceptionally(failure); }
                @Override public void onResponse(Call ignored, Response value) {
                    try (value) {
                        if (!value.isSuccessful() || value.body() == null) throw new IOException("Parser HTTP " + value.code());
                        response.complete(JsonParseResult.read(value.body().string(), headers, value.request().url().toString()));
                    } catch (Exception failure) { response.completeExceptionally(failure); }
                }
            });
            try {
                JsonParseResult parsed = response.get(timeout, TimeUnit.NANOSECONDS);
                if (!isHttp(parsed.url)) continue;
                // A resolved candidate is a single URL, so it cannot accidentally re-enter foreground parsing.
                com.google.gson.JsonObject json = App.gson().toJsonTree(result).getAsJsonObject();
                json.addProperty("jx", 0);
                json.add("header", App.gson().toJsonTree(parsed.headers));
                result = Result.objectFrom(json.toString());
                result.setUrl(parsed.url);
                result.setParse(0);
                result.setPlayUrl("");
                return Source.get().requiresExtraction(result) ? null : result;
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw cancelled;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
                // Automatic mode can try another JSON line within the same total deadline.
            } finally { call.cancel(); }
        }
        return null;
    }

    private static boolean isHttp(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
