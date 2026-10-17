package com.kukuqi.tvbox.osc.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Sources may share mutable Spider instances. Serialize one site's calls, not every site. */
public final class SourceAccess {
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private SourceAccess() {}
    public static <T> T call(String key, Callable<T> operation) throws Exception {
        ReentrantLock lock = LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock(true));
        lock.lockInterruptibly();
        try { return operation.call(); }
        finally { lock.unlock(); }
    }
}
