package com.github.catvod;

import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Prefers;

/** Values are JSON text in Java; scripting bridges decode them. */
public final class Local {
    private final String prefix;
    public Local(String site) { prefix = "source_local_" + Crypto.md5(site) + "_"; }
    public String get(String key) { return Prefers.getString(prefix + key, "null"); }
    public void set(String key, String value) { Prefers.put(prefix + key, value); }
    public void delete(String key) { Prefers.remove(prefix + key); }
}
