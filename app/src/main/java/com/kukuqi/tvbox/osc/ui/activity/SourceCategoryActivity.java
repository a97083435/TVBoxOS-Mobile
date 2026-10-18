package com.kukuqi.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.widget.*;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.google.gson.*;
import com.kukuqi.tvbox.osc.bean.MovieSort;
import com.kukuqi.tvbox.osc.ui.fragment.GridFragment;

/** Source category navigation preserves the originating site and its filter values. */
public final class SourceCategoryActivity extends AppCompatActivity {
    private static final int CONTENT = 0x5b010001;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        Button back = new Button(this); back.setText(getIntent().getStringExtra("name")); back.setOnClickListener(v -> onBackPressed());
        layout.addView(back, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout frame = new FrameLayout(this); frame.setId(CONTENT); layout.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(layout);
        if (state != null) return;
        MovieSort.SortData sort = new MovieSort.SortData(getIntent().getStringExtra("id"), getIntent().getStringExtra("name"));
        sort.flag = getIntent().getStringExtra("type_flag"); sort.sourceKey = getIntent().getStringExtra("sourceKey");
        sort.cardStyle = getIntent().getStringExtra("cate");
        try {
            JsonObject filter = JsonParser.parseString(getIntent().getStringExtra("filter")).getAsJsonObject();
            for (var item : filter.entrySet()) sort.filterSelect.put(item.getKey(), item.getValue().getAsString());
        } catch (RuntimeException ignored) {}
        getSupportFragmentManager().beginTransaction().replace(CONTENT, GridFragment.newInstance(sort), "category").commit();
    }
    @Override public void onBackPressed() {
        var fragment = getSupportFragmentManager().findFragmentByTag("category");
        if (fragment instanceof GridFragment grid && grid.restoreView()) return;
        super.onBackPressed();
    }
}
