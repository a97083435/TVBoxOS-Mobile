package com.github.catvod;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.lang.ref.WeakReference;

/** Host API available to compileOnly source plugins. */
public final class Runtime {
    private static volatile WeakReference<Activity> current = new WeakReference<>(null);
    private Runtime() {}
    public static Context context() { return Init.context().getApplicationContext(); }
    public static Activity activity() {
        Activity value = current.get();
        return value == null || value.isFinishing() || value.isDestroyed() ? null : value;
    }
    public static void resumed(Activity activity) { current = new WeakReference<>(activity); }
    public static void paused(Activity activity) { if (current.get() == activity) current.clear(); }
    public static void toast(String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context(), text, Toast.LENGTH_LONG).show());
    }
}
