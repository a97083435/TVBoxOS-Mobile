package com.kukuqi.tvbox.engine.runtime;
import com.kukuqi.tvbox.engine.App;
import java.util.Locale;
public final class BdjSupport {
    private static Object runtime;
    private BdjSupport() {}
    public static boolean isDisc(String url) {
        if (url == null) return false;
        String value = url.toLowerCase(Locale.ROOT).split("[?#]", 2)[0];
        return value.startsWith("bd://") || value.startsWith("br://") || value.startsWith("bluray://") || value.endsWith(".iso") || value.endsWith("/index.bdmv") || value.endsWith("/bdmv") || value.endsWith("/bdmv/");
    }
    public static synchronized String prepare() throws Exception {
        RuntimeModules.loader(App.get(), RuntimeModule.BDJ);
        if (runtime == null) runtime = RuntimeModules.create(App.get(), RuntimeModule.BDJ);
        return (String) RuntimeModules.call(runtime, "prepare", new Class<?>[0]);
    }
    /** Optional for ordinary main-title playback; explicit menu requests require the module. */
    public static boolean shouldPrepare(String url) {
        if (!isDisc(url)) return false;
        try { RuntimeModules.installed(App.get(), RuntimeModule.BDJ); return true; }
        catch (Exception missing) { return url.contains("://menu/"); }
    }
}
