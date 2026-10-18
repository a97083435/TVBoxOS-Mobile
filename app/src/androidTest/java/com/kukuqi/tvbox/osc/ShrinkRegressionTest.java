package com.kukuqi.tvbox.osc;

import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import com.kukuqi.tvbox.osc.ui.activity.MainActivity;

/** Runtime reflection paths that cannot be discovered from ordinary Java calls. */
public class ShrinkRegressionTest extends InstrumentationTestCase {
    private boolean normalStart;
    @Override protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().runOnMainSync(() -> {
            normalStart = com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart;
            com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart = true;
        });
    }
    @Override protected void tearDown() throws Exception {
        getInstrumentation().runOnMainSync(() -> com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart = normalStart);
        super.tearDown();
    }
    public void testSettingsSelectionDialogInflatesAfterOptimization() throws Exception {
        MainActivity page = (MainActivity) getInstrumentation().startActivitySync(
                new Intent(getInstrumentation().getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_START_DESTINATION, R.id.navigation_dashboard));
        try {
            for (int i = 0; i < 100; i++) {
                View[] row = {null};
                getInstrumentation().runOnMainSync(() -> row[0] = page.findViewById(R.id.llHomeRec));
                if (row[0] != null) break;
                Thread.sleep(100);
            }
            getInstrumentation().runOnMainSync(() -> {
                View row = page.findViewById(R.id.llHomeRec);
                assertNotNull(row);
                row.performClick();
            });
            for (int i = 0; i < 100; i++) {
                android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
                if (root != null && !root.findAccessibilityNodeInfosByText("站点推荐").isEmpty()) {
                    getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                    return;
                }
                Thread.sleep(100);
            }
            fail("Settings selection dialog did not appear");
        } finally { getInstrumentation().runOnMainSync(page::finish); }
    }

    public void testPersistedWorkManagerInputMergerCanBeCreated() throws Exception {
        // WorkManager stores this name in its database across app updates.
        Object merger = Class.forName("androidx.work.OverwritingInputMerger",
                true, getInstrumentation().getTargetContext().getClassLoader())
                .getDeclaredConstructor().newInstance();
        assertNotNull(merger);
    }
}
