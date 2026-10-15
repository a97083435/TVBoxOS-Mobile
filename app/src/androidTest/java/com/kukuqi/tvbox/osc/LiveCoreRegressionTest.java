package com.kukuqi.tvbox.osc;

import android.test.InstrumentationTestCase;
import com.kukuqi.tvbox.engine.App;
import com.kukuqi.tvbox.engine.api.LiveApi;
import com.kukuqi.tvbox.engine.bean.Channel;
import com.kukuqi.tvbox.engine.bean.EpgData;
import com.kukuqi.tvbox.engine.bean.Result;
import com.google.gson.JsonObject;
import com.kukuqi.tvbox.osc.core.EpgCoreBridge;
import com.kukuqi.tvbox.osc.core.LiveCoreBridge;
import com.kukuqi.tvbox.osc.bean.LiveChannelItem;
import com.kukuqi.tvbox.osc.util.epg.EpgSchedule;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;

public class LiveCoreRegressionTest extends InstrumentationTestCase {
    public void testCatchupPreservesHeadersDrmAndFormatThroughHostModels() throws Exception {
        String json="{\"lives\":[{\"name\":\"fixture\",\"header\":{\"X-Live\":\"core\"},\"catchup\":{\"type\":\"append\",\"source\":\"?begin=${utc:}&end=${utcend:}\"},\"groups\":[{\"name\":\"group\",\"channel\":[{\"name\":\"news\",\"urls\":[\"https://example.org/live.mp4\"],\"format\":\"video/mp4\",\"drm\":{\"type\":\"clearkey\",\"key\":\"https://example.org/license\",\"header\":{\"X-License\":\"core\"}}}]}]}]}";
        JsonObject host=LiveCoreBridge.parse("https://example.org/config.json",json).get(0).getAsJsonObject().getAsJsonArray("channels").get(0).getAsJsonObject();
        LiveChannelItem item=new LiveChannelItem();item.setCoreChannel(App.gson().fromJson(host.get("core"),Channel.class));
        Channel channel=LiveCoreBridge.channel(item);assertTrue(channel.hasCatchup());
        EpgData programme=new EpgData();programme.setStartTime(1700000000000L);programme.setEndTime(1700003600000L);
        Result result=LiveApi.getUrl(channel,programme);
        assertEquals("https://example.org/live.mp4?begin=1700000000&end=1700003600",result.getUrl().v());
        assertEquals("core",result.getHeader().get("X-Live"));assertEquals("video/mp4",result.getFormat());
        assertEquals("clearkey",result.getDrm().getType());assertEquals("core",result.getDrm().getHeader().get("X-License"));
    }
    public void testJsonProgrammeCrossesMidnightWithCorrectZone() throws Exception {
        TimeZone zone=TimeZone.getTimeZone("Asia/Shanghai");LiveChannelItem channel=new LiveChannelItem();channel.setChannelName("news");channel.setTvgId("news");
        String date="2026-10-13";
        EpgSchedule schedule=EpgCoreBridge.parse("{\"date\":\""+date+"\",\"epg_data\":[{\"title\":\"midnight\",\"start\":\"23:30\",\"end\":\"00:30\"}]}",channel,date,zone);
        assertEquals(1,schedule.programmes.size());EpgSchedule.Programme programme=schedule.programmes.get(0);
        assertEquals(3600000L,programme.end-programme.start);
        assertEquals(LocalDate.parse(date).atTime(23,30).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),programme.start);
    }
}
