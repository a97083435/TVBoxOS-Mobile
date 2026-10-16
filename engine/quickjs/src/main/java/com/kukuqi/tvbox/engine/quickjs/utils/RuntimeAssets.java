package com.kukuqi.tvbox.engine.quickjs.utils;
import android.content.Context;
import com.github.catvod.utils.Path;
/** Runtime assets come from the module APK; app-private data stays with the host. */
public final class RuntimeAssets {
    private static Context context;
    public static void init(Context value) { context = value; }
    public static String read(String path) {
        try (java.io.InputStream in = context.getAssets().open(path.replace("assets://", ""))) { return Path.read(in); }
        catch (Exception e) { throw new IllegalStateException("QuickJS资源缺失：" + path, e); }
    }
}
