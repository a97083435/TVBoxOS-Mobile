package com.kukuqi.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.kukuqi.tvbox.osc.R;
import com.kukuqi.tvbox.osc.api.ApiConfig;
import com.kukuqi.tvbox.osc.bean.SourceBean;
import com.kukuqi.tvbox.osc.bean.Subscription;
import com.kukuqi.tvbox.osc.event.SourceChangedEvent;
import com.kukuqi.tvbox.osc.ui.settings.ThemeColors;
import com.kukuqi.tvbox.osc.util.HawkConfig;
import com.kukuqi.tvbox.osc.util.PlayerHelper;
import com.kukuqi.tvbox.osc.util.Utils;
import com.orhanobut.hawk.Hawk;
import org.greenrobot.eventbus.EventBus;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Functional settings panels shared by My and the standalone settings host. */
public final class FeatureSettingsDialog {
    private final Activity activity;
    private final LinearLayout content;
    private final MaxHeightLinearLayout root;
    private final Dialog dialog;
    private FeatureSettingsDialog(Activity activity, String title) {
        this.activity = activity;
        root = new MaxHeightLinearLayout(activity);
        root.setMaxHeight(Math.min((int) (activity.getResources().getDisplayMetrics().heightPixels * 0.82f), dp(680)));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(10), dp(18), dp(14));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(activity.getColor(R.color.text_foreground));
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setPadding(dp(4), dp(12), dp(4), dp(12));
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(activity);
        close.setText("×");
        close.setTextSize(28);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(activity.getColor(R.color.text_foreground));
        close.setContentDescription("关闭");
        close.setFocusable(true);
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
        root.addView(header);

        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(2), 0, dp(2), dp(4));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog = new Dialog(activity, R.style.CustomDialogStyle);
        dialog.setContentView(root);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> styleWindow(dialog));
    }
    private int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density); }
    private TextView row(String label, Runnable click) {
        TextView text = new TextView(activity); text.setText(label); text.setTextSize(16); text.setPadding(dp(12), dp(15), dp(12), dp(15));
        text.setTextColor(activity.getColor(click == null ? R.color.text_sub_foreground : R.color.text_foreground));
        text.setAlpha(click == null ? 0.82f : 1f);
        text.setMinHeight(dp(52));
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setFocusable(click != null);
        if (click != null) {
            TypedValue value = new TypedValue();
            activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
            text.setBackgroundResource(value.resourceId);
            text.setOnClickListener(v -> click.run());
        }
        content.addView(text); return text;
    }

    private void styleWindow(Dialog target) {
        Window window = target.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = Math.min(dp(400), (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.dimAmount = 0.28f;
        window.setAttributes(params);
        window.setWindowAnimations(R.style.DialogFadeAnimation);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        root.setBackgroundResource(R.drawable.bg_dialog);
        root.setElevation(dp(12));
    }
    private static final class MaxHeightLinearLayout extends LinearLayout {
        private int maxHeight = Integer.MAX_VALUE;
        MaxHeightLinearLayout(Activity activity) { super(activity); }
        void setMaxHeight(int value) { maxHeight = value; }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, capped);
        }
    }
    public static void select(Activity activity, String title, String[] names, int selected, IntConsumer onSelect) {
        FeatureSettingsDialog panel = new FeatureSettingsDialog(activity, title);
        int checked = Math.max(-1, Math.min(names.length - 1, selected));
        for (int i = 0; i < names.length; i++) {
            int index = i;
            TextView option = panel.row(names[i], () -> {
                onSelect.accept(index);
                panel.dialog.dismiss();
            });
            option.setSelected(i == checked);
            if (i == checked) {
                option.setTextColor(ThemeColors.color(activity));
                option.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                option.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.checkbox_on_background, 0);
            }
        }
        panel.dialog.show();
    }
    public static void actions(Activity activity, String title, String[] names, IntConsumer onSelect) {
        select(activity, title, names, -1, onSelect);
    }
    public static void sourceHistory(Activity activity, Runnable manageVod, Runnable manageLive, Runnable manageWallpaper) {
        FeatureSettingsDialog panel = new FeatureSettingsDialog(activity, "已保存来源");
        panel.row("选择和切换来源请进入下方管理页面", null);
        String currentVod = Hawk.get(HawkConfig.API_URL, "");
        ArrayList<Subscription> vod = Hawk.get(HawkConfig.SUBSCRIPTIONS, new ArrayList<>());
        panel.row("点播来源 · " + vod.size(), null);
        for (Subscription item : vod) {
            if (item == null || item.getUrl() == null || item.getUrl().trim().isEmpty()) continue;
            panel.row(item.getName() + (item.getUrl().equals(currentVod) ? " · 当前" : ""), null);
        }
        int[] kinds = {com.kukuqi.tvbox.osc.util.SourceLibrary.LIVE, com.kukuqi.tvbox.osc.util.SourceLibrary.WALLPAPER};
        String[] labels = {"直播来源", "壁纸来源"};
        String[] selected = {Hawk.get(HawkConfig.LIVE_URL, ""), com.kukuqi.tvbox.osc.util.WallpaperManager.get().getSelectionValue()};
        for (int i = 0; i < kinds.length; i++) {
            ArrayList<com.kukuqi.tvbox.osc.util.SourceLibrary.Entry> entries = com.kukuqi.tvbox.osc.util.SourceLibrary.list(kinds[i]);
            panel.row(labels[i] + " · " + entries.size(), null);
            for (com.kukuqi.tvbox.osc.util.SourceLibrary.Entry item : entries)
                panel.row(item.name + (item.url.equals(selected[i]) ? " · 当前" : ""), null);
        }
        panel.row("管理点播来源", () -> { panel.dialog.dismiss(); manageVod.run(); });
        panel.row("管理直播来源", () -> { panel.dialog.dismiss(); manageLive.run(); });
        panel.row("管理壁纸来源", () -> { panel.dialog.dismiss(); manageWallpaper.run(); });
        panel.dialog.show();
    }
    private static void message(Activity activity, String title, String detail) {
        FeatureSettingsDialog panel = new FeatureSettingsDialog(activity, title);
        panel.row(detail, null);
        panel.row("知道了", panel.dialog::dismiss);
        panel.dialog.show();
    }
    private static void confirm(Activity activity, String title, String detail, Runnable action) {
        FeatureSettingsDialog panel = new FeatureSettingsDialog(activity, title);
        panel.row(detail, null);
        panel.row("确认", () -> {
            action.run();
            panel.dialog.dismiss();
        });
        panel.row("取消", panel.dialog::dismiss);
        panel.dialog.show();
    }
    private static void edit(Activity activity, String title, String detail, String value, String hint, Consumer<String> save) {
        FeatureSettingsDialog panel = new FeatureSettingsDialog(activity, title);
        panel.row(detail, null);
        EditText input = new EditText(activity);
        input.setText(value);
        input.setHint(hint);
        input.setSingleLine(false);
        input.setTextColor(activity.getColor(R.color.text_foreground));
        input.setHintTextColor(activity.getColor(R.color.text_sub_foreground));
        input.setPadding(panel.dp(12), panel.dp(12), panel.dp(12), panel.dp(12));
        panel.content.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.row("保存", () -> {
            save.accept(input.getText().toString().trim());
            panel.dialog.dismiss();
        });
        panel.dialog.show();
        input.requestFocus();
        Window window = panel.dialog.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void choice(String label, String key, String[] names, int defaultValue) {
        TextView[] view = new TextView[1];
        Runnable render = () -> view[0].setText(label + "：" + names[Math.max(0, Math.min(names.length - 1, Hawk.get(key, defaultValue)))]);
        view[0] = row(label, () -> select(activity, label, names, Hawk.get(key, defaultValue), index -> {
            Hawk.put(key, index); render.run();
        })); render.run();
    }
    private void toggle(String label, String key, boolean defaultValue) {
        TextView[] view = new TextView[1];
        Runnable render = () -> view[0].setText(label + "：" + (Hawk.get(key, defaultValue) ? "开" : "关"));
        view[0] = row(label, () -> { Hawk.put(key, !Hawk.get(key, defaultValue)); render.run(); }); render.run();
    }
    private void toggle(String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        TextView[] view = new TextView[1];
        Runnable render = () -> view[0].setText(label + "：" + (getter.getAsBoolean() ? "开" : "关"));
        view[0] = row(label, () -> { setter.accept(!getter.getAsBoolean()); render.run(); });
        render.run();
    }
    private void choice(String label, String[] names, int[] values, IntSupplier getter, IntConsumer setter) {
        TextView[] view = new TextView[1];
        Runnable render = () -> {
            int current = getter.getAsInt(), selected = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == current) { selected = i; break; }
            view[0].setText(label + "：" + names[selected]);
        };
        view[0] = row(label, () -> {
            int current = getter.getAsInt(), selected = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == current) { selected = i; break; }
            select(activity, label, names, selected, index -> {
                setter.accept(values[index]); render.run();
            });
        });
        render.run();
    }
    private void number(String label, String key, int[] values, String unit, int defaultValue) {
        TextView[] view = new TextView[1]; String[] names = new String[values.length]; for (int i = 0; i < values.length; i++) names[i] = values[i] + unit;
        Runnable render = () -> view[0].setText(label + "：" + Hawk.get(key, defaultValue) + unit);
        view[0] = row(label, () -> actions(activity, label, names, index -> { Hawk.put(key, values[index]); render.run(); })); render.run();
    }
    public static void danmaku(Activity activity) {
        FeatureSettingsDialog panel = new FeatureSettingsDialog(activity, "弹幕设置");
        panel.toggle("加载弹幕", "danmaku_load", false); panel.toggle("显示弹幕", "danmaku_show", true);
        panel.toggle("自动匹配", "danmaku_auto", false); panel.toggle("优先使用源返回的弹幕", "danmaku_spider_first", true);
        TextView[] api = new TextView[1]; Runnable render = () -> api[0].setText("匹配接口：" + (Hawk.get("danmaku_api_url", "").isEmpty() ? "跟随点播源" : Hawk.get("danmaku_api_url", "")));
        api[0] = panel.row("匹配接口", () -> edit(activity, "弹幕匹配接口",
                "支持 {name}、{episode} GET 模板；无模板时发送 name、episode JSON。返回弹幕名称和地址列表。",
                Hawk.get("danmaku_api_url", ""), "接口地址，留空跟随点播源", value -> {
                    Hawk.put("danmaku_api_url", value); render.run();
                })); render.run();
        panel.number("不透明度", "danmaku_opacity", new int[]{25, 50, 75, 100}, "%", 100);
        panel.number("同屏数量上限", "danmaku_density", new int[]{10, 30, 60, 100, 150, 300, 500}, "条", 150);
        TextView[] scale = new TextView[1]; scale[0] = panel.row("文字大小：" + Hawk.get("danmaku_scale", 1f), () -> actions(activity, "文字大小",
                new String[]{"0.5", "0.75", "1.0", "1.5", "2.0", "3.0"}, index -> { float value = new float[]{.5f,.75f,1f,1.5f,2f,3f}[index]; Hawk.put("danmaku_scale", value); scale[0].setText("文字大小：" + value); }));
        TextView[] area = new TextView[1]; area[0] = panel.row("显示区域：" + (int) (Hawk.get("danmaku_area", .5f) * 100) + "%", () -> actions(activity, "显示区域",
                new String[]{"25%", "50%", "75%", "100%"}, index -> { float value = (index + 1) * .25f; Hawk.put("danmaku_area", value); area[0].setText("显示区域：" + (int) (value * 100) + "%"); }));
        TextView[] duration = new TextView[1]; duration[0] = panel.row("通过时间：" + Hawk.get("danmaku_duration", 8000L) / 1000 + "秒", () -> actions(activity, "弹幕通过时间",
                new String[]{"3秒", "5秒", "8秒", "10秒", "15秒"}, index -> { long value = new long[]{3000,5000,8000,10000,15000}[index]; Hawk.put("danmaku_duration", value); duration[0].setText("通过时间：" + value / 1000 + "秒"); }));
        TextView[] offset = new TextView[1]; offset[0] = panel.row("时间调整：" + Hawk.get("danmaku_offset", 0L) / 1000 + "秒", () -> actions(activity, "时间调整（正值延后）",
                new String[]{"-30秒", "-10秒", "-5秒", "0秒", "+5秒", "+10秒", "+30秒"}, index -> { long value = new long[]{-30000,-10000,-5000,0,5000,10000,30000}[index]; Hawk.put("danmaku_offset", value); offset[0].setText("时间调整：" + value / 1000 + "秒"); }));
        panel.dialog.show();
    }
    public static void playback(Activity activity, Runnable refresh) {
        FeatureSettingsDialog panel = new FeatureSettingsDialog(activity, "播放设置");
        panel.choice("默认播放器", new String[]{"Exo播放器", "MPV播放器"}, new int[]{2, 1},
                () -> Hawk.get(HawkConfig.PLAY_TYPE, 2), value -> Hawk.put(HawkConfig.PLAY_TYPE, value));
        panel.choice("渲染方式", HawkConfig.PLAY_RENDER, new String[]{"TextureView", "SurfaceView"}, 0);
        panel.choice("画面缩放", HawkConfig.PLAY_SCALE, new String[]{"默认", "16:9", "4:3", "填充", "原始", "裁剪"}, 0);
        panel.choice("后台播放", HawkConfig.BACKGROUND_PLAY_TYPE, new String[]{"关闭", "开启", "画中画"}, 2);
        panel.toggle("广告过滤", HawkConfig.VIDEO_PURIFY, true); panel.toggle("播放缓存", HawkConfig.IJK_CACHE_PLAY, false);
        panel.row("高级播放（下次播放生效）", null);
        panel.toggle("音频直通", com.kukuqi.tvbox.engine.setting.DecodeSetting::isAudioPassThrough,
                com.kukuqi.tvbox.engine.setting.DecodeSetting::putAudioPassThrough);
        panel.toggle("静音片段跳过（Exo）", com.kukuqi.tvbox.engine.setting.SpeedSetting::isSkipSilence,
                com.kukuqi.tvbox.engine.setting.SpeedSetting::putSkipSilence);
        panel.toggle("MPV Vulkan渲染", com.kukuqi.tvbox.engine.setting.PlayerSetting::isMpvVulkan,
                com.kukuqi.tvbox.engine.setting.PlayerSetting::putMpvVulkan);
        panel.toggle("MPV GPU Next", com.kukuqi.tvbox.engine.setting.PlayerSetting::isMpvGpuNext,
                com.kukuqi.tvbox.engine.setting.PlayerSetting::putMpvGpuNext);
        panel.choice("MPV双字幕", new String[]{"默认关闭", "关闭", "自动选择"}, new int[]{-3, -2, -1},
                com.kukuqi.tvbox.engine.setting.SubtitleSetting::getSecondaryMode,
                com.kukuqi.tvbox.engine.setting.SubtitleSetting::putSecondaryMode);
        panel.choice("副字幕位置", new String[]{"顶部", "10%", "25%", "50%", "75%", "底部"}, new int[]{0, 10, 25, 50, 75, 100},
                () -> Math.round(com.kukuqi.tvbox.engine.setting.SubtitleSetting.getSecondaryPosition()),
                value -> com.kukuqi.tvbox.engine.setting.SubtitleSetting.putSecondaryPosition(value));
        panel.dialog.setOnDismissListener(d -> refresh.run()); panel.dialog.show();
    }
    public static void modules(Activity activity) {
        FeatureSettingsDialog panel = new FeatureSettingsDialog(activity, "模块管理");
        panel.row("播放核心", null);
        panel.row("Exo / Media3 · 已启用", null);
        panel.row("MPV · 已启用", null);
        panel.row("解析运行时", null);
        panel.row("JAR Spider · 已启用", null);
        panel.row("QuickJS · 已启用", null);
        panel.row("Python · 已启用", null);
        panel.row("内置组件随应用更新；来源模块可重载或清理", null);
        File[] caches = {com.github.catvod.utils.Path.jar(), com.github.catvod.utils.Path.js(), com.github.catvod.utils.Path.py()};
        String[] names = {"JAR 缓存", "JS 缓存", "Python 缓存"};
        TextView[] sizes = new TextView[caches.length];
        for (int i = 0; i < caches.length; i++) sizes[i] = panel.row(names[i], null);
        Runnable updateSizes = () -> {
            for (int i = 0; i < caches.length; i++)
                sizes[i].setText(names[i] + "：" + android.text.format.Formatter.formatFileSize(activity, com.github.catvod.utils.Path.size(caches[i])));
        };
        updateSizes.run();
        boolean[] busy = {false};
        TextView status = panel.row("重新加载模块", () -> {});
        Consumer<String> finish = message -> activity.runOnUiThread(() -> {
            busy[0] = false;
            if (activity.isDestroyed() || activity.isFinishing() || !panel.dialog.isShowing()) return;
            status.setText(message);
            updateSizes.run();
        });
        status.setOnClickListener(v -> {
            if (busy[0]) return;
            busy[0] = true;
            status.setText("正在重新加载…");
            com.kukuqi.tvbox.osc.util.HeavyTaskUtil.executeNewTask(() -> {
                try {
                    ApiConfig.get().resetModules();
                    String spider = ApiConfig.get().getSpider();
                    if (spider == null || spider.isEmpty()) {
                        finish.accept("模块已重置，将在使用时加载");
                        return;
                    }
                    ApiConfig.get().loadJar(true, spider, new ApiConfig.LoadConfigCallback() {
                        public void success() { finish.accept("模块重新加载完成（点击可重载）"); }
                        public void retry() { }
                        public void error(String message) { finish.accept("加载失败，请检查来源与网络（点击重试）"); }
                    });
                } catch (Exception e) { finish.accept("加载失败，请重试"); }
            });
        });
        panel.row("清除已下载的模块缓存", () -> {
            if (busy[0]) return;
            confirm(activity, "清除模块缓存", "仅清理下载缓存，保留来源和本地文件。下次使用时重新加载。", () -> {
                if (busy[0]) return;
                busy[0] = true;
                status.setText("正在清除缓存…");
                com.kukuqi.tvbox.osc.util.HeavyTaskUtil.executeNewTask(() -> {
                    try {
                        ApiConfig.get().resetModules();
                        for (File cache : caches) com.kukuqi.tvbox.osc.util.CacheCleaner.clear(cache);
                        finish.accept("缓存已清除，点击可重载模块");
                    } catch (Exception e) { finish.accept("部分缓存未清除，请重试"); }
                });
            });
        });
        panel.dialog.show();
    }
    public static void homeSite(Activity activity) {
        List<SourceBean> sites = ApiConfig.get().getSourceBeanList();
        if (sites.isEmpty()) { message(activity, "首页站点", "当前点播源尚未加载，请先选择有效来源"); return; }
        String[] names = new String[sites.size()]; int selected = 0;
        for (int i = 0; i < sites.size(); i++) { names[i] = sites.get(i).getName(); if (sites.get(i).getKey().equals(ApiConfig.get().getHomeSourceBean().getKey())) selected = i; }
        select(activity, "首页站点", names, selected, index -> {
            ApiConfig.get().setSourceBean(sites.get(index)); EventBus.getDefault().post(new SourceChangedEvent());
        });
    }
}
