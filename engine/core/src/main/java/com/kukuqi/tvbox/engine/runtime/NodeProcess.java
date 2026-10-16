package com.kukuqi.tvbox.engine.runtime;
import android.content.Context;
public final class NodeProcess {
    private NodeProcess() {}
    public static boolean isRuntime(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 28) return android.app.Application.getProcessName().endsWith(":nodejs");
        android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null && manager.getRunningAppProcesses() != null)
            for (var process : manager.getRunningAppProcesses())
                if (process.pid == android.os.Process.myPid()) return process.processName.endsWith(":nodejs");
        return false;
    }
}
