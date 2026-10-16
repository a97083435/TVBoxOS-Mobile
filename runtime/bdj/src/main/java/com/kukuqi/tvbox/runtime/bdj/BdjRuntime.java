package com.kukuqi.tvbox.runtime.bdj;
import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/** JRE and libbluray classes are installed privately, only when a disc is opened. */
public final class BdjRuntime {
    static { System.loadLibrary("tvbox_bdj"); }
    private static native void configure(String home, String jar, String storage);
    public static native String probe(String home, String jar);
    private static boolean ready;
    private final Context context;
    public BdjRuntime(Context context) { this.context = context; }
    public synchronized String prepare() throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) throw new IllegalStateException("BD-J 需要在后台加载");
        File directory = new File(context.getFilesDir(), "bdj/zero-8u482-v1");
        File home = new File(directory, "j2re-image");
        if (ready) return home.getAbsolutePath();
        File marker = new File(directory, ".complete");
        if (!marker.isFile()) {
            directory.mkdirs();
            try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("bdj/jre.zip"))) {
                ZipEntry entry; long total = 0; byte[] buffer = new byte[32768];
                while ((entry = zip.getNextEntry()) != null) {
                    File target = new File(directory, entry.getName());
                    if (!target.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) throw new SecurityException("JRE 路径无效");
                    if (entry.isDirectory()) { target.mkdirs(); continue; }
                    target.getParentFile().mkdirs();
                    try (OutputStream out = new FileOutputStream(target)) {
                        int n; while ((n = zip.read(buffer)) != -1) { total += n; if (total > 160L*1024*1024) throw new IOException("JRE 过大"); out.write(buffer,0,n); }
                    }
                }
            }
            for (String name : new String[]{"libbluray-j2se-1.4.1.jar", "libbluray-awt-j2se-1.4.1.jar"})
                try (var in = context.getAssets().open("bdj/" + name)) { Files.copy(in, new File(directory, name).toPath(), StandardCopyOption.REPLACE_EXISTING); }
            // Older libbluray ARM64 builds searched lib/arm instead of lib/aarch64.
            File alias = new File(home, "lib/arm");
            if (!alias.exists()) android.system.Os.symlink("aarch64", alias.getAbsolutePath());
            Files.writeString(marker.toPath(), "zero-8u482/libbluray-1.4.1");
        }
        File storage = new File(context.getFilesDir(), "bdj/storage"); storage.mkdirs();
        configure(home.getAbsolutePath(), new File(directory, "libbluray-j2se-1.4.1.jar").getAbsolutePath(), storage.getAbsolutePath());
        ready = true; return home.getAbsolutePath();
    }
}
