package com.kukuqi.tvbox.osc.core;

import com.kukuqi.tvbox.engine.bean.SkipSegment;
import java.util.List;

/** Resolve per-episode source skips while preserving explicit user intro/outro settings. */
public final class SkipPolicy {
    public record Decision(long position, boolean next) {}
    private SkipSegment pending;
    public void reset() { pending = null; }
    public Decision progress(List<SkipSegment> segments, long position, long duration,
                             long userOpening, long userEnding, boolean hasNext) {
        if (pending != null && (position < pending.start() || (pending.end() != null && position >= pending.end()))) pending = null;
        for (SkipSegment segment : segments) {
            if (segment.equals(pending) || position < segment.start()) continue;
            if (segment.type().equals("opening") && userOpening > 0) continue;
            if (segment.type().equals("ending") && userEnding > 0) continue;
            if (segment.end() != null && position >= segment.end()) continue;
            long target = segment.end() == null ? duration : segment.end();
            if (duration > 0) target = Math.min(target, duration);
            if (segment.end() == null && hasNext) { pending = segment; return new Decision(0, true); }
            if (target <= position) continue;
            pending = segment; return new Decision(target, false);
        }
        return null;
    }
}
