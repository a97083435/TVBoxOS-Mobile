package com.kukuqi.tvbox.engine.api;

import androidx.annotation.NonNull;

import com.kukuqi.tvbox.engine.R;
import com.kukuqi.tvbox.engine.api.config.LiveConfig;
import com.kukuqi.tvbox.engine.api.parser.EpgParser;
import com.kukuqi.tvbox.engine.api.parser.LiveParser;
import com.kukuqi.tvbox.engine.bean.Channel;
import com.kukuqi.tvbox.engine.bean.Epg;
import com.kukuqi.tvbox.engine.bean.EpgData;
import com.kukuqi.tvbox.engine.bean.Group;
import com.kukuqi.tvbox.engine.bean.Live;
import com.kukuqi.tvbox.engine.bean.Result;
import com.kukuqi.tvbox.engine.player.extractor.Source;
import com.kukuqi.tvbox.engine.utils.Formatters;
import com.github.catvod.net.OkHttp;

import java.time.LocalDate;
import java.time.ZoneId;

public class LiveApi {

    public static void parse(@NonNull Live item) throws Exception {
        LiveParser.start(item.recent());
        item.getGroups().removeIf(Group::isEmpty);
        if (item.getGroups().isEmpty() || item.getGroups().get(0).isKeep()) return;
        item.getGroups().add(0, Group.create(R.string.core_keep));
        LiveConfig.get().applyKeepsToGroups(item.getGroups());
    }

    public static boolean parseXml(@NonNull Live item) {
        return item.getEpgXml().stream().map(url -> startXml(item, url)).reduce(false, Boolean::logicalOr);
    }

    @NonNull
    public static Epg getEpg(@NonNull Channel item, @NonNull ZoneId zoneId) {
        String today = LocalDate.now(zoneId).format(Formatters.DATE);
        for (int offset : new int[]{-1, 0, 1}) fetchEpgDay(item, zoneId, offset);
        return item.getDataList().stream().filter(epg -> epg.equal(today)).findFirst().orElseGet(Epg::new).selected();
    }

    @NonNull
    public static Result getUrl(@NonNull Channel item) throws Exception {
        Source.get().stop();
        Result result = item.result();
        result.setUrl(Source.get().fetch(result));
        return result;
    }

    @NonNull
    public static Result getUrl(@NonNull Channel item, @NonNull EpgData data) throws Exception {
        Result result = getUrl(item);
        result.setUrl(item.getCatchup().format(result.getRealUrl(), data));
        if (item.isRtsp()) result.getHeader().put("rtsp_range", data.getRange());
        return result;
    }

    private static boolean startXml(Live item, String url) {
        try {
            EpgParser.start(item, url);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void fetchEpgDay(@NonNull Channel item, @NonNull ZoneId zoneId, int offset) {
        String date = LocalDate.now(zoneId).plusDays(offset).format(Formatters.DATE);
        String url = item.getEpg().replace("{date}", date);
        boolean need = url.startsWith("http") && item.getDataList().stream().noneMatch(epg -> epg.equal(date));
        if (need) item.setData(Epg.objectFrom(OkHttp.string(url), item.getTvgId(), zoneId));
    }
}
