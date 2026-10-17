package com.kukuqi.tvbox.engine.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import dalvik.system.DexClassLoader;

/** Loads separately installed, signed runtime APKs. Never initializes optional code at app startup. */
public final class RuntimeModules {
    private static final Map<RuntimeModule, Loaded> loaded = new EnumMap<>(RuntimeModule.class);
    private static long lastNotice;
    private record Loaded(String path, long version, ClassLoader loader, Context context) {}
    /** Each APK ships the desugared Java methods it uses. The host's shrunk copy is incomplete for modules. */
    private static final class ModuleClassLoader extends DexClassLoader {
        ModuleClassLoader(String apk, String cache, String libraries, ClassLoader parent) {
            super(apk, cache, libraries, parent);
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("j$.")) return super.loadClass(name, resolve);
            synchronized (this) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try { type = findClass(name); }
                    catch (ClassNotFoundException absent) { return super.loadClass(name, resolve); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
    private RuntimeModules() {}

    @SuppressWarnings("deprecation")
    public static void verify(Context host, RuntimeModule module, PackageInfo info) throws Exception {
        if (!module.packageName(host).equals(info.packageName)) throw new SecurityException("模块包名不匹配");
        ApplicationInfo app = info.applicationInfo;
        if (app == null || app.metaData == null || !module.contract().equals(app.metaData.getString("tvbox.runtime.contract")))
            throw new SecurityException("模块接口不兼容");
        if (!"arm64-v8a".equals(app.metaData.getString("tvbox.runtime.abi"))) throw new SecurityException("模块架构不匹配");
        if (app.minSdkVersion > Build.VERSION.SDK_INT || !Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))
            throw new IllegalStateException("设备不支持此模块");
        Signature[] expected = host.getPackageManager().getPackageInfo(host.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
        if (expected == null || expected.length == 0 || info.signatures == null || !Arrays.equals(expected, info.signatures))
            throw new SecurityException("模块签名与主程序不一致");
    }
    @SuppressWarnings("deprecation")
    public static PackageInfo installed(Context host, RuntimeModule module) throws Exception {
        PackageInfo info = host.getPackageManager().getPackageInfo(module.packageName(host), PackageManager.GET_META_DATA | PackageManager.GET_SIGNATURES);
        verify(host, module, info);
        return info;
    }
    public static synchronized String status(Context host, RuntimeModule module) {
        try {
            PackageInfo info = installed(host, module);
            Loaded active = loaded.get(module);
            if (active != null && (!active.path.equals(info.applicationInfo.sourceDir) || active.version != info.versionCode)) return "已更新 · 重启应用生效";
            return "已安装 " + info.versionName;
        } catch (PackageManager.NameNotFoundException e) { return "未安装 · 点击下载"; }
        catch (Exception e) { return "不可用 · " + e.getMessage(); }
    }
    public static synchronized boolean isLoaded(RuntimeModule module) { return loaded.containsKey(module); }
    public static synchronized ClassLoader loader(Context host, RuntimeModule module) {
        try {
            PackageInfo info = installed(host, module);
            Loaded active = loaded.get(module);
            if (active != null) {
                if (!active.path.equals(info.applicationInfo.sourceDir) || active.version != info.versionCode)
                    throw new IllegalStateException("模块已更新，请重启应用");
                return active.loader;
            }
            ApplicationInfo app = info.applicationInfo;
            File nativeDir = new File(app.nativeLibraryDir);
            if (!nativeDir.isDirectory()) throw new IllegalStateException("模块原生库未安装，请重新安装模块");
            ClassLoader loader = new ModuleClassLoader(app.sourceDir, host.getCodeCacheDir().getAbsolutePath(), app.nativeLibraryDir, host.getClassLoader());
            Context resources = host.createPackageContext(info.packageName, 0);
            Context context = new ContextWrapper(host.getApplicationContext()) {
                @Override public AssetManager getAssets() { return resources.getAssets(); }
                @Override public Resources getResources() { return resources.getResources(); }
                @Override public ClassLoader getClassLoader() { return loader; }
                @Override public Context getApplicationContext() { return this; }
            };
            loaded.put(module, new Loaded(app.sourceDir, info.versionCode, loader, context));
            return loader;
        } catch (Exception e) {
            notifyFailure(host, module);
            throw new IllegalStateException(module.title + " 模块不可用，请到“我的 → 模块管理”安装。" + e.getMessage(), e);
        }
    }
    public static synchronized Object create(Context host, RuntimeModule module) {
        try {
            ClassLoader loader = loader(host, module);
            return loader.loadClass(module.entry).getConstructor(Context.class).newInstance(loaded.get(module).context);
        } catch (Exception e) {
            String message = module.title + " 模块不可用，请到“我的 → 模块管理”安装。" + cause(e).getMessage();
            notifyFailure(host, module);
            throw new IllegalStateException(message, cause(e));
        }
    }
    private static void notifyFailure(Context host, RuntimeModule module) {
        if (android.os.SystemClock.elapsedRealtime() - lastNotice <= 5000) return;
        lastNotice = android.os.SystemClock.elapsedRealtime();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(host.getApplicationContext(), module.title + " 模块不可用，请到“我的 → 模块管理”安装", android.widget.Toast.LENGTH_LONG).show());
    }
    public static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        try { return target.getClass().getMethod(method, types).invoke(target, args); }
        catch (InvocationTargetException e) {
            Throwable cause = cause(e);
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }
    private static Throwable cause(Throwable e) {
        while (e instanceof InvocationTargetException && e.getCause() != null) e = e.getCause();
        return e;
    }
}
