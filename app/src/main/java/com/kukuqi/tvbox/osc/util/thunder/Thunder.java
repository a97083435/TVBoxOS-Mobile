package com.kukuqi.tvbox.osc.util.thunder;
/** Legacy URL recognition; execution is owned by the protocol extractor. */
public final class Thunder {
    public static boolean isFtp(String url) { return url != null && url.startsWith("ftp://"); }
}
