package com.kukuqi.tvbox.engine.event;

import com.kukuqi.tvbox.engine.bean.Config;
import com.kukuqi.tvbox.engine.bean.Device;
import com.kukuqi.tvbox.engine.bean.History;

import org.greenrobot.eventbus.EventBus;

public record CastEvent(Config config, Device device, History history) {

    public static void post(Config config, Device device, History history) {
        EventBus.getDefault().post(new CastEvent(config, device, history));
    }
}
