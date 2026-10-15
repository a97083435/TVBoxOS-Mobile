package com.kukuqi.tvbox.osc.core;

import com.kukuqi.tvbox.engine.api.parser.EpgParser;
import com.kukuqi.tvbox.engine.bean.*;
import com.kukuqi.tvbox.osc.bean.LiveChannelItem;
import com.kukuqi.tvbox.osc.util.epg.EpgSchedule;
import java.util.*;

public final class EpgCoreBridge {
    public static EpgSchedule parse(String text, LiveChannelItem item, String date, TimeZone zone) throws Exception {
        Channel channel = LiveCoreBridge.channel(item);
        List<Epg> days;
        if (text.trim().startsWith("<")) {
            Live live = new Live("节目表","");
            Group group = Group.create("节目表",false);
            group.getChannel().add(channel); live.getGroups().add(group);
            EpgParser.readText(live,text);
            days = channel.getDataList();
        } else {
            Epg day = Epg.objectFrom(text,channel.getTvgId(),zone.toZoneId());
            days = Collections.singletonList(day);
        }
        List<EpgSchedule.Programme> programmes = new ArrayList<>();
        for (Epg day : days) if (date.equals(day.getDate())) for (EpgData row : day.getList())
            programmes.add(new EpgSchedule.Programme(row.getTitle(),row.getStartTime(),row.getEndTime()));
        return new EpgSchedule(date,programmes);
    }
}
