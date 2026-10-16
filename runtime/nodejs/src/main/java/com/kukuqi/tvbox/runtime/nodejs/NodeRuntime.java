package com.kukuqi.tvbox.runtime.nodejs;

import android.content.Context;
import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.UUID;

/** Invoked only in the host's disposable :nodejs process. */
public final class NodeRuntime {
    static { System.loadLibrary("node"); System.loadLibrary("tvbox_node"); }
    private static native int run(String[] args);
    private final Context context;
    private boolean started;
    public NodeRuntime(Context context) { this.context = context; }
    public synchronized String[] start() throws Exception {
        if (started) throw new IllegalStateException("Node.js 已启动");
        File root = new File(context.getFilesDir(), "nodejs");
        if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("无法创建 Node.js 目录");
        File script = new File(root, "server.cjs");
        try (var in = context.getAssets().open("nodejs/server.cjs")) { Files.copy(in, script.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        int port; try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        String token = UUID.randomUUID().toString();
        started = true;
        new Thread(() -> { run(new String[]{"node", script.getAbsolutePath(), root.getAbsolutePath(), String.valueOf(port), token}); android.os.Process.killProcess(android.os.Process.myPid()); }, "node-main").start();
        return new String[]{"http://127.0.0.1:" + port, token};
    }
}
