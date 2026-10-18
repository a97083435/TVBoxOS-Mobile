package com.kukuqi.tvbox.osc.util;

import android.content.Intent;
import android.net.Uri;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.*;
import android.view.View;
import android.widget.TextView;
import com.google.gson.*;
import com.kukuqi.tvbox.osc.ui.activity.SourceCategoryActivity;

public final class SourceLinks {
    private SourceLinks() {}
    public static void bind(TextView view, String prefix, String html, String source) {
        SpannableStringBuilder text = new SpannableStringBuilder(prefix).append(Html.fromHtml(html == null ? "" : html, Html.FROM_HTML_MODE_LEGACY));
        for (URLSpan span : text.getSpans(0, text.length(), URLSpan.class)) {
            int start = text.getSpanStart(span), end = text.getSpanEnd(span); text.removeSpan(span);
            String url = span.getURL();
            if (!url.startsWith("catvod://")) continue;
            try {
                JsonObject target = JsonParser.parseString(Uri.decode(url.substring(9))).getAsJsonObject();
                if (!target.has("id") || !target.get("id").isJsonPrimitive()) continue;
                text.setSpan(new ClickableSpan() {
                    @Override public void onClick(View widget) {
                        Intent intent = new Intent(widget.getContext(), SourceCategoryActivity.class)
                                .putExtra("sourceKey", source).putExtra("id", target.get("id").getAsString())
                                .putExtra("name", target.has("name") ? target.get("name").getAsString() : text.subSequence(start, end).toString())
                                .putExtra("type_flag", target.has("type_flag") ? target.get("type_flag").getAsString() : null)
                                .putExtra("filter", target.has("filter") ? target.get("filter").toString() : "{}");
                        widget.getContext().startActivity(intent);
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (RuntimeException ignored) {}
        }
        view.setText(text); view.setMovementMethod(LinkMovementMethod.getInstance());
    }
}
