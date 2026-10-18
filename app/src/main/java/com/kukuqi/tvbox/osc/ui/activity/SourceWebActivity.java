package com.kukuqi.tvbox.osc.ui.activity;

import android.app.Activity;
import android.os.*;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import com.google.gson.*;
import com.kukuqi.tvbox.osc.core.WebCookiePolicy;

/** In-app content window; sources cannot launch arbitrary external application intents. */
public final class SourceWebActivity extends Activity {
    private WebView web;
    private FrameLayout root;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private WebCookiePolicy policy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (isFinishing() || web == null) return;
            if (policy.complete(web.getUrl(), url -> CookieManager.getInstance().getCookie(url))) { setResult(RESULT_OK); finish(); }
            else main.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        JsonObject command;
        try { command = JsonParser.parseString(getIntent().getStringExtra("web")).getAsJsonObject(); }
        catch (RuntimeException error) { finish(); return; }
        String url = command.has("url") ? command.get("url").getAsString() : "";
        if (!WebCookiePolicy.http(url)) { finish(); return; }
        policy = WebCookiePolicy.restore(command, state == null ? null : state.getString("cookieBaseline"), address -> CookieManager.getInstance().getCookie(address));
        root = new FrameLayout(this); setContentView(root);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); root.addView(layout);
        boolean content = command.has("mode") && "view".equals(command.get("mode").getAsString());
        if (!content) {
            Button back = new Button(this); back.setText("返回 · TVboxOSC"); back.setOnClickListener(view -> onBackPressed());
            layout.addView(back, new LinearLayout.LayoutParams(-1, -2));
        }
        web = new WebView(this); layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return !WebCookiePolicy.http(request.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String address) { return !WebCookiePolicy.http(address); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreen != null) { callback.onCustomViewHidden(); return; }
                fullscreen = view; fullscreenCallback = callback; root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }
            @Override public void onHideCustomView() { hideFullscreen(); }
        });
        if (state == null || web.restoreState(state) == null) web.loadUrl(url);
        main.postDelayed(watch, 500);
    }
    private void hideFullscreen() {
        if (fullscreen == null) return; root.removeView(fullscreen); fullscreen = null;
        if (fullscreenCallback != null) { fullscreenCallback.onCustomViewHidden(); fullscreenCallback = null; }
    }
    @Override public void onBackPressed() { if (fullscreen != null) hideFullscreen(); else if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); if (web != null) web.saveState(state); if (policy != null) state.putString("cookieBaseline", policy.snapshot()); }
    @Override protected void onDestroy() { main.removeCallbacks(watch); hideFullscreen(); if (web != null) { web.stopLoading(); web.destroy(); web = null; } super.onDestroy(); }
}
