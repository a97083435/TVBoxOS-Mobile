package com.kukuqi.tvbox.osc.update;

import com.kukuqi.tvbox.osc.util.RuntimeTransfer;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.Request;
import okhttp3.Response;

/** Race bounded byte samples, then leave integrity verification to the full download. */
final class UpdateRouteSelector {
    private static final int SAMPLE = 256 * 1024;
    static List<String> select(List<String> routes, long size, RuntimeTransfer.Requester requester,
                               BooleanSupplier cancelled) throws InterruptedException {
        if (routes.size() < 2) return routes;
        ExecutorService pool = Executors.newFixedThreadPool(routes.size());
        CompletionService<String> completion = new ExecutorCompletionService<>(pool);
        List<Future<String>> tasks = new ArrayList<>();
        int sample = (int) Math.min(size, SAMPLE);
        try {
            for (String route : routes) tasks.add(completion.submit(() -> {
                if (cancelled.getAsBoolean()) throw new IOException("下载已取消");
                Request request = new Request.Builder().url(route).header("User-Agent", "TVboxOSC")
                        .header("Accept-Encoding", "identity").header("Range", "bytes=0-" + (sample - 1)).build();
                try (Response response = requester.execute(request)) {
                    String expected = "bytes 0-" + (sample - 1) + "/" + size;
                    if (response.code() != 206 || response.body() == null || !expected.equals(response.header("Content-Range")))
                        throw new IOException("测速响应范围无效");
                    try (InputStream input = response.body().byteStream()) {
                        byte[] buffer = new byte[16384]; int total = 0, count;
                        while (total < sample && (count = input.read(buffer, 0, Math.min(buffer.length, sample - total))) != -1) {
                            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("测速已取消");
                            total += count;
                        }
                        if (total != sample) throw new IOException("测速响应不完整");
                    }
                    return route;
                }
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            for (int pending = routes.size(); pending > 0;) {
                if (cancelled.getAsBoolean()) return routes;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<String> done = completion.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
                if (done == null) continue;
                pending--;
                try {
                    String first = done.get();
                    List<String> ordered = new ArrayList<>(routes);
                    ordered.remove(first); ordered.add(0, first);
                    return ordered;
                } catch (java.util.concurrent.ExecutionException ignored) { }
            }
            return routes;
        } finally {
            tasks.forEach(task -> task.cancel(true));
            pool.shutdownNow();
        }
    }
}
