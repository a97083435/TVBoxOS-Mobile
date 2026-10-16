package com.kukuqi.tvbox.osc.util;

import com.orhanobut.hawk.Hawk;

/** Keep persisted IDs stable when retiring the legacy homepage provider. */
public final class HomeRecommendationSettings {
    private HomeRecommendationSettings() {}

    public static void migrate() {
        int saved = Hawk.get(HawkConfig.HOME_REC, 1);
        Hawk.put(HawkConfig.HOME_REC, saved == 2 ? 2 : 1);
    }
}
