package com.taocrypt.omc;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Invisible activity that runs the system file picker for a WebView (main page or floating window). */
public final class PickActivity extends Activity {
    private static final int REQ = 7;
    private boolean launched;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            launched = state.getBoolean("launched");
        }
        if (launched) {
            return;
        }
        String[] mimes = getIntent().getStringArrayExtra("mimes");
        Intent get = new Intent(Intent.ACTION_GET_CONTENT);
        get.addCategory(Intent.CATEGORY_OPENABLE);
        if (mimes != null && mimes.length == 1) {
            get.setType(mimes[0]);
        } else {
            get.setType("*/*");
            if (mimes != null && mimes.length > 1) get.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        }
        get.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, getIntent().getBooleanExtra("multiple", false));
        try {
            startActivityForResult(Intent.createChooser(get, "选择文件"), REQ);
            launched = true;
        } catch (RuntimeException e) {
            WebFiles.deliver(null);
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("launched", launched);
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        Uri[] result = null;
        if (code == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        WebFiles.deliver(result);
        finish();
        overridePendingTransition(0, 0);
    }
}
