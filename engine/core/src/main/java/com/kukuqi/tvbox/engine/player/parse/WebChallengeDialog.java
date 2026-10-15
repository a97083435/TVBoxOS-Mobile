package com.kukuqi.tvbox.engine.player.parse;

import android.content.DialogInterface;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.kukuqi.tvbox.engine.App;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class WebChallengeDialog {

    private final AlertDialog dialog;

    public static WebChallengeDialog create(View view) {
        return new WebChallengeDialog(view);
    }

    private WebChallengeDialog(View view) {
        this.dialog = new MaterialAlertDialogBuilder(App.activity()).setView(view).create();
        this.dialog.setOnDismissListener((DialogInterface.OnDismissListener) view);
    }

    public WebChallengeDialog show() {
        initDialog();
        return this;
    }

    public void dismiss() {
        dialog.setOnDismissListener(null);
        dialog.dismiss();
    }

    private void initDialog() {
        dialog.show();
    }
}
