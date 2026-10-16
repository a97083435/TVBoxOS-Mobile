package com.kukuqi.tvbox.engine.player.extractor;
import android.net.Uri;
import com.kukuqi.tvbox.engine.App;
import com.kukuqi.tvbox.engine.bean.Episode;
import com.kukuqi.tvbox.engine.runtime.RuntimeModule;
import com.kukuqi.tvbox.engine.runtime.RuntimeModules;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
public class Thunder implements Source.Extractor {
    private Object runtime;
    private synchronized Object runtime() {
        if (runtime == null) runtime = RuntimeModules.create(App.get(), RuntimeModule.THUNDER);
        return runtime;
    }
    @Override public boolean match(Uri uri) { return List.of("magnet", "ed2k").contains(uri.getScheme()); }
    @Override public String fetch(String url) throws Exception { return (String) RuntimeModules.call(runtime(), "fetch", new Class<?>[]{String.class}, url); }
    @Override public void stop() { invokeIfLoaded("stop"); }
    @Override public void exit() { invokeIfLoaded("exit"); }
    private void invokeIfLoaded(String method) {
        if (runtime != null) try { RuntimeModules.call(runtime, method, new Class<?>[0]); } catch (Exception e) { e.printStackTrace(); }
    }
    public record Parser(String url) implements Callable<List<Episode>> {
        private static final Pattern PATTERN = Pattern.compile("(magnet|thunder|ed2k):.*");
        public static boolean match(String url) { return PATTERN.matcher(url).find() || (!url.startsWith("magnet") && url.split(";")[0].toLowerCase(java.util.Locale.ROOT).endsWith(".torrent")); }
        public static Parser get(String url) { return new Parser(url); }
        @Override @SuppressWarnings("unchecked") public List<Episode> call() throws Exception {
            Object runtime = RuntimeModules.create(App.get(), RuntimeModule.THUNDER);
            return (List<Episode>) RuntimeModules.call(runtime, "parse", new Class<?>[]{String.class}, url);
        }
    }
}
