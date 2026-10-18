package com.kukuqi.tvbox.engine.player.exo;

import androidx.media3.common.*;
import androidx.media3.common.text.CueGroup;
import com.kukuqi.tvbox.engine.player.engine.PlayerEngine.SecondarySubtitleState;
import com.kukuqi.tvbox.engine.setting.SubtitleSetting;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Secondary text output belongs to this session and is cleared on media transitions. */
final class ExoSubtitleController {
    private final ExoPlayerSession session;
    private TrackSelectionOverride explicit;
    private Consumer<CueGroup> output;
    private CueGroup cues = CueGroup.EMPTY_TIME_ZERO;
    ExoSubtitleController(ExoPlayerSession session) { this.session = session; }
    void onCues(CueGroup value) { cues = value; if (output != null) output.accept(value); }
    void setOutput(Consumer<CueGroup> value) { output = value; if (value != null) value.accept(cues); }
    void clear() { explicit = null; session.selectSecondary(null, false); onCues(CueGroup.EMPTY_TIME_ZERO); }
    void release() { output = null; cues = CueGroup.EMPTY_TIME_ZERO; }
    void onTracksChanged() {
        if (explicit != null && !getSecondarySubtitleState().secondaryCandidates().contains(explicit)) {
            explicit = null;
            onCues(CueGroup.EMPTY_TIME_ZERO);
            applySubtitleStyle();
        }
    }
    void applySubtitleStyle() {
        session.selectSecondary(explicit, SubtitleSetting.getSecondaryMode() == SubtitleSetting.SECONDARY_MODE_AUTO);
        if (output != null) output.accept(cues);
    }
    void setSecondarySubtitleSelection(TrackSelectionOverride value) {
        if (value != null && !getSecondarySubtitleState().secondaryCandidates().contains(value)) return;
        explicit = value;
        onCues(CueGroup.EMPTY_TIME_ZERO);
        applySubtitleStyle();
    }
    SecondarySubtitleState getSecondarySubtitleState() {
        TrackSelectionOverride primary = null;
        var candidates = new ArrayList<TrackSelectionOverride>();
        for (Tracks.Group group : session.player().getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) if (group.isTrackSupported(i)) {
                var selection = new TrackSelectionOverride(group.getMediaTrackGroup(), i);
                if (group.isTrackSelected(i)) primary = selection; else candidates.add(selection);
            }
        }
        return new SecondarySubtitleState(primary, candidates.contains(explicit) ? explicit : null, candidates, false);
    }
}
