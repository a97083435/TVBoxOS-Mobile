package com.kukuqi.tvbox.osc.util;

import android.content.Intent;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import com.kukuqi.tvbox.osc.bean.Movie;
import com.kukuqi.tvbox.osc.core.SourceAccess;
import com.kukuqi.tvbox.osc.core.WebCookiePolicy;
import com.kukuqi.tvbox.osc.ui.activity.SourceWebActivity;
import com.kukuqi.tvbox.osc.viewmodel.SourceViewModel;
import com.kukuqi.tvbox.engine.api.SiteApi;
import com.google.gson.JsonObject;

public final class SourceActions {
    public static final int WEB_RESULT = 3201;
    private SourceActions() {}
    public static boolean folder(android.content.Context context, Movie.Video card) {
        if (!"folder".equals(card.tag) && card.cate == null) return false;
        context.startActivity(new Intent(context, com.kukuqi.tvbox.osc.ui.activity.SourceCategoryActivity.class)
                .putExtra("id", card.id).putExtra("name", card.name).putExtra("sourceKey", card.sourceKey)
                .putExtra("type_flag", "1").putExtra("cate", card.cate));
        return true;
    }
    public static boolean run(androidx.fragment.app.FragmentActivity owner, Movie.Video card, Runnable refresh) {
        return execute(owner, () -> !owner.isFinishing() && !owner.isDestroyed(), card, refresh,
                intent -> owner.startActivityForResult(intent, WEB_RESULT));
    }
    public static boolean run(Fragment owner, Movie.Video card, Runnable refresh) {
        android.view.View view = owner.getView();
        return execute(owner.requireActivity(), () -> owner.isAdded() && owner.getView() == view, card, refresh,
                intent -> owner.startActivityForResult(intent, WEB_RESULT));
    }
    private static boolean execute(android.app.Activity owner, java.util.function.BooleanSupplier alive, Movie.Video card, Runnable refresh, java.util.function.Consumer<Intent> open) {
        if (card.action == null || card.action.isEmpty()) return false;
        var site = com.kukuqi.tvbox.engine.api.config.VodConfig.get().getSite(card.sourceKey);
        SourceViewModel.spThreadPool.execute(() -> {
            try {
                var result = SourceAccess.call(card.sourceKey, () -> SiteApi.action(card.sourceKey, card.action));
                owner.runOnUiThread(() -> {
                    if (!alive.getAsBoolean() || com.kukuqi.tvbox.engine.api.config.VodConfig.get().getSite(card.sourceKey) != site) return;
                    if (result.hasMsg()) Toast.makeText(owner, result.getMsg(), Toast.LENGTH_LONG).show();
                    JsonObject web = result.getWeb();
                    if (web != null && web.has("url") && web.get("url").isJsonPrimitive() && WebCookiePolicy.http(web.get("url").getAsString()))
                        open.accept(new Intent(owner, SourceWebActivity.class).putExtra("web", web.toString()));
                    else refresh.run();
                });
            } catch (Exception error) {
                owner.runOnUiThread(() -> {
                    if (alive.getAsBoolean()) Toast.makeText(owner, "来源操作失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
        return true;
    }
}
