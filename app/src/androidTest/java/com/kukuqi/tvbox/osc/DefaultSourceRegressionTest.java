package com.kukuqi.tvbox.osc;

import android.test.InstrumentationTestCase;
import com.kukuqi.tvbox.engine.api.Decoder;
import com.kukuqi.tvbox.engine.api.SiteApi;
import com.kukuqi.tvbox.engine.api.config.VodConfig;
import com.kukuqi.tvbox.engine.bean.Result;
import com.google.gson.JsonObject;
import com.kukuqi.tvbox.osc.util.ConfigCompat;

/** Network-dependent diagnostic; run separately from deterministic regressions. */
public class DefaultSourceRegressionTest extends InstrumentationTestCase {
    public void testDefaultSourceLoadsRealHomepage() throws Exception {
        String url = "http://www.饭太硬.cc/tv";
        String content;
        try { content = Decoder.getJson(url,"TVboxCoreTest"); }
        catch (Throwable failure) { throw new AssertionError("Default source fetch failed", failure); }
        JsonObject document = ConfigCompat.resolve(new ConfigCompat.Resource(url,content), address -> {
            try { return new ConfigCompat.Resource(address, Decoder.getJson(address,"TVboxCoreTest")); }
            catch (Throwable failure) { throw new Exception(failure); }
        });
        VodConfig.get().accept(url,"",document);
        android.util.Log.i("TVboxCoreTest", "Default home: " + VodConfig.get().getHome().getKey() + ", api=" + VodConfig.get().getHome().getApi());
        Result home = SiteApi.homeContent(VodConfig.get().getHome());
        assertFalse("Real source returned no categories or recommendations", home.getTypes().isEmpty() && home.getList().isEmpty());
        com.kukuqi.tvbox.engine.bean.Site guard=VodConfig.get().getSites().stream().filter(site -> site.getApi().contains("DouDouGuard")).findFirst().orElse(null);
        assertNotNull("The source no longer contains the originally crashing DouDouGuard plugin",guard);
        Result guardHome=SiteApi.homeContent(guard);
        assertFalse("DouDouGuard returned no homepage data",guardHome.getTypes().isEmpty() && guardHome.getList().isEmpty());
        android.util.Log.i("TVboxCoreTest", "DouDouGuard homepage passed: " + guard.getKey());
    }
}
