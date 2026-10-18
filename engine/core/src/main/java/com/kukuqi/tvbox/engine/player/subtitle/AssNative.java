package com.kukuqi.tvbox.engine.player.subtitle;

/** Small JNI adapter using the libass already exported by the bundled player. */
final class AssNative {
    static final boolean AVAILABLE;
    static {
        boolean loaded;
        try { System.loadLibrary("mpv"); System.loadLibrary("tvbox_ass"); loaded = available(); }
        catch (LinkageError unavailable) { loaded = false; }
        AVAILABLE = loaded;
    }
    static native boolean available();
    static native long open(String config, String fonts, String family);
    static native long read(long context, byte[] script);
    static native long newTrack(long context, byte[] codecPrivate);
    static native void chunk(long track, byte[] data, long startMs, long durationMs, boolean reset);
    static native long endMs(long track);
    static native int[] render(long context, long track, long timeMs);
    static native void freeTrack(long track);
    static native void close(long context);
}
