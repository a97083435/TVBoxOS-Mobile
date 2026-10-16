package com.kukuqi.tvbox.osc.update;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.kukuqi.tvbox.osc.BuildConfig;
import com.kukuqi.tvbox.osc.util.UpdateManifest;
import com.kukuqi.tvbox.osc.util.RuntimeDownload;
import com.kukuqi.tvbox.osc.util.RuntimeTransfer;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Persistent background work, independent of the About view or current Activity. */
public class UpdateWorker extends Worker {
    private static final String MANIFEST_URL = "https://raw.githubusercontent.com/kukuqi666/TVboxOSC/main/update.json";
    // Metadata uses a short deadline; APKs have an independent resumable transfer budget.
    private static final String[] ROUTES = {"https://gh-proxy.com/", ""};
    private static final ReentrantLock DOWNLOAD_LOCK = new ReentrantLock();
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build();
    private final OkHttpClient downloadClient = client.newBuilder().readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.MINUTES).followSslRedirects(false).build();
    private final OkHttpClient probeClient = downloadClient.newBuilder().connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS).callTimeout(7, TimeUnit.SECONDS).build();
    private final Set<Call> downloadCalls = ConcurrentHashMap.newKeySet(), probeCalls = ConcurrentHashMap.newKeySet();
    private volatile Call running;
    private final UpdateStore store;

    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
        store = new UpdateStore(context);
    }

    @NonNull @Override public Result doWork() {
        try {
            return getInputData().getBoolean("download", false) ? download() : check();
        } catch (Exception error) {
            android.util.Log.w("AppUpdate", "Background update failed", error);
            if (!isStopped()) {
                UpdateManifest manifest = store.manifest();
                long retained = manifest == null ? 0 : store.partial(manifest).length();
                boolean resumable = getInputData().getBoolean("download", false) && manifest != null
                        && retained > 0 && retained < manifest.size;
                store.state(UpdateStore.ERROR, error instanceof IllegalStateException ? error.getMessage()
                        : resumable ? "下载中断，已保留进度，重试将继续下载" : "更新暂不可用，请检查网络后重试",
                        resumable ? (int) (retained * 100 / manifest.size) : 0);
            }
            return !isStopped() && !(error instanceof IllegalStateException) && getRunAttemptCount() < 2 ? Result.retry() : Result.failure();
        } finally {
            running = null;
            downloadCalls.forEach(Call::cancel); downloadCalls.clear();
            probeCalls.forEach(Call::cancel); probeCalls.clear();
        }
    }

    private Result check() throws Exception {
        if (UpdateStore.DOWNLOADING.equals(store.state()) || UpdateStore.VERIFYING.equals(store.state())) return Result.success();
        boolean ready = store.hasReadyUpdate();
        if (!ready) store.state(UpdateStore.CHECKING, "正在检查新版本…", 0);
        Exception last = null;
        for (String route : ROUTES) {
            if (isStopped()) return Result.failure();
            try (Response response = request(route + MANIFEST_URL + "?t=" + System.currentTimeMillis())) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("无法连接更新服务");
                String json = response.body().string();
                if (UpdateManifest.legacyIsNotNewer(json, BuildConfig.VERSION_NAME)) {
                    store.prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();
                    if (!ready) store.state(UpdateStore.CURRENT, "当前已是最新版本", 0);
                    return Result.success();
                }
                UpdateManifest manifest = new UpdateManifest(json, getApplicationContext().getPackageName());
                if (isStopped()) return Result.failure();
                store.prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();
                if (!manifest.isNewer(BuildConfig.VERSION_CODE)) {
                    if (!ready) store.state(UpdateStore.CURRENT, "当前已是最新版本", 0);
                    return Result.success();
                }
                UpdateManifest previous = store.manifest();
                if (ready && previous != null && previous.versionCode >= manifest.versionCode) return Result.success();
                store.prefs.edit().putString("manifest", json).apply();
                store.state(UpdateStore.AVAILABLE, store.autoDownload() ? "发现 v" + manifest.version + "，等待 Wi-Fi 后台下载"
                        : "发现 v" + manifest.version + "，可手动下载", 0);
                if (store.autoDownload()) UpdateCoordinator.download(getApplicationContext(), false);
                return Result.success();
            } catch (Exception error) { last = error; }
        }
        if (ready) return Result.success(); // Keep an already verified download available during network outages.
        throw last == null ? new IOException("无法获取更新信息") : last;
    }

    private Result download() throws Exception {
        // A cancelled/replaced WorkManager task must release the shared partial file before its replacement writes.
        while (!DOWNLOAD_LOCK.tryLock(200, TimeUnit.MILLISECONDS)) if (isStopped()) return Result.failure();
        try { return downloadLocked(); }
        finally { DOWNLOAD_LOCK.unlock(); }
    }

    private Result downloadLocked() throws Exception {
        if (isStopped()) return Result.failure();
        boolean manual = getInputData().getBoolean("manual", false);
        if (!manual && !store.autoDownload()) return Result.success();
        if (store.hasReadyUpdate()) return Result.success();
        UpdateManifest manifest = store.manifest();
        if (manifest == null || !manifest.isNewer(BuildConfig.VERSION_CODE)) return Result.success();
        File apk = store.apk(manifest);
        File directory = apk.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建下载目录");
        File part = store.partial(manifest);
        File[] stale = directory.listFiles((dir, name) -> (name.endsWith(".part") || name.endsWith(".part.apk"))
                && !name.equals(part.getName()));
        if (stale != null) for (File file : stale) file.delete();
        RuntimeTransfer transfer = new RuntimeTransfer(this::requestDownload);
        Exception last = null;
        store.state(UpdateStore.DOWNLOADING, "正在选择下载线路…", (int) Math.min(100, part.length() * 100 / manifest.size));
        for (String route : downloadRoutes(manifest)) {
            if (isStopped()) return Result.failure();
            store.state(UpdateStore.DOWNLOADING, "正在连接 · " + RuntimeTransfer.routeName(route), (int) (part.length() * 100 / manifest.size));
            try {
                transfer.download(route, part, manifest.size, message -> {
                    if (!isStopped()) store.state(UpdateStore.DOWNLOADING, "正在后台下载 v" + manifest.version + " · " + message,
                            (int) Math.min(100, part.length() * 100 / manifest.size));
                }, this::isStopped);
                if (isStopped()) return Result.failure();
                store.state(UpdateStore.VERIFYING, "下载完成，正在校验更新包…", 100);
                manifest.verifyDownload(part);
                UpdateInstaller.verifyArchive(getApplicationContext(), part, manifest);
                if (isStopped()) return Result.failure();
                if (apk.exists() && !apk.delete()) throw new IOException("无法替换更新包");
                if (!part.renameTo(apk)) throw new IOException("无法保存更新包");
                store.state(UpdateStore.READY, "v" + manifest.version + " 已准备好，可安装更新", 100);
                UpdateCoordinator.notifyReady(getApplicationContext(), manifest.version);
                return Result.success();
            } catch (Exception error) {
                last = error;
                // Keep network failures resumable, but never reuse a complete file that failed integrity/signature checks.
                if (error instanceof SecurityException || error instanceof IllegalStateException || part.length() >= manifest.size) part.delete();
                if (isStopped()) return Result.failure();
            }
        }
        throw last == null ? new IOException("下载失败") : last;
    }

    protected Response request(String url) throws IOException {
        running = client.newCall(new Request.Builder().url(url).header("User-Agent", "TVboxOSC")
                .header("Cache-Control", "no-cache").build());
        if (isStopped()) throw new IOException("更新任务已停止");
        return running.execute();
    }

    protected Response requestDownload(Request request) throws IOException { return execute(downloadClient, request, downloadCalls); }

    protected List<String> downloadRoutes(UpdateManifest manifest) throws Exception {
        List<String> routes = RuntimeTransfer.routes(manifest.apkUrl, RuntimeDownload.routeMode());
        if (RuntimeDownload.routeMode() != 0 || store.partial(manifest).length() == manifest.size) return routes;
        try { return UpdateRouteSelector.select(routes, manifest.size, request -> execute(probeClient, request, probeCalls), this::isStopped); }
        finally { probeCalls.forEach(Call::cancel); }
    }

    private Response execute(OkHttpClient client, Request request, Set<Call> calls) throws IOException {
        Call call = client.newCall(request); calls.add(call);
        if (isStopped() || Thread.currentThread().isInterrupted()) { call.cancel(); throw new IOException("更新任务已停止"); }
        return call.execute();
    }

    @Override public void onStopped() {
        Call call = running;
        if (call != null) call.cancel();
        downloadCalls.forEach(Call::cancel);
        probeCalls.forEach(Call::cancel);
        super.onStopped();
    }
}
