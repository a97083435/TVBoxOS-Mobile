package com.kukuqi.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.kukuqi.tvbox.engine.bean.CastVideo;
import com.kukuqi.tvbox.engine.bean.Device;
import com.kukuqi.tvbox.engine.dlna.DLNACast;
import com.kukuqi.tvbox.engine.dlna.DLNACastManager;
import com.kukuqi.tvbox.osc.util.Utils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DLNA device picker rendered with the existing TVboxOSC dialog style. */
public final class CastDialog implements DLNACastManager.DeviceListener {
    private final Activity activity;
    private final CastVideo video;
    private final Runnable onCasted;
    private final DLNACastManager manager = DLNACastManager.get();
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final LinearLayout rows;
    private final TextView status;
    private final AlertDialog dialog;
    private boolean released;

    private CastDialog(Activity activity, CastVideo video, Runnable onCasted) {
        this.activity = activity;
        this.video = video;
        this.onCasted = onCasted;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(6), dp(20), dp(8));
        status = text("正在搜索同一网络中的投屏设备…", false);
        content.addView(status);
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        dialog = new AlertDialog.Builder(activity)
                .setTitle("投屏播放")
                .setView(scroll)
                .setNeutralButton("重新搜索", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            styleWindow();
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> search());
        });
        dialog.setOnDismissListener(ignored -> release());
    }

    public static void show(Activity activity, CastVideo video, Runnable onCasted) {
        if (video == null || video.url() == null || video.url().isEmpty()) {
            Toast.makeText(activity, "播放地址尚未准备好，请开始播放后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        CastDialog picker = new CastDialog(activity, video, onCasted);
        picker.dialog.show();
        picker.manager.setDeviceListener(picker);
        picker.manager.init(activity);
        picker.refresh(picker.manager.getRegistered());
    }

    private void search() {
        devices.clear();
        rows.removeAllViews();
        status.setText("正在重新搜索投屏设备…");
        status.setVisibility(View.VISIBLE);
        manager.search();
        refresh(manager.getRegistered());
    }

    private void refresh(List<Device> discovered) {
        for (Device device : discovered) devices.put(device.getUuid(), device);
        render();
    }

    private void render() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        rows.removeAllViews();
        if (devices.isEmpty()) {
            status.setVisibility(View.VISIBLE);
            status.setText("暂未发现设备。请确认手机和电视连接到同一网络，然后重新搜索。");
            return;
        }
        status.setVisibility(View.GONE);
        for (Device device : devices.values()) {
            TextView row = text(device.getName().isEmpty() ? "未命名投屏设备" : device.getName(), true);
            row.setOnClickListener(v -> cast(device));
            rows.addView(row);
        }
    }

    private void cast(Device device) {
        status.setVisibility(View.VISIBLE);
        status.setText("正在连接 " + device.getName() + "…");
        new DLNACast(video, () -> {
            if (onCasted != null) onCasted.run();
            Toast.makeText(activity, "已发送到 " + device.getName(), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }).cast(device);
    }

    @Override
    public void onDeviceAdded(Device device) {
        devices.put(device.getUuid(), device);
        render();
    }

    @Override
    public void onDeviceRemoved(Device device) {
        devices.remove(device.getUuid());
        render();
    }

    private TextView text(String value, boolean button) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(button ? 17 : 14);
        view.setPadding(dp(4), dp(button ? 16 : 10), dp(4), dp(button ? 16 : 10));
        view.setFocusable(button);
        if (button) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    private void styleWindow() {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(com.kukuqi.tvbox.osc.R.drawable.bg_dialog);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f);
        params.dimAmount = 0.28f;
        window.setAttributes(params);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    private void release() {
        if (released) return;
        released = true;
        manager.setDeviceListener(null);
        manager.release(activity);
    }
}
