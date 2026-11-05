package com.taocrypt.omc;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile "Oh My CPA": same as long-pressing the floating button - minimised floating window, then the home screen. */
public final class FloatTileService extends TileService {
    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(i);
            return;
        }
        if (FloatService.isShowing()) {
            FloatService.hide(this);
        } else {
            OmcService.start(this);
            Prefs.saveFloatMini(this, true);
            FloatService.show(this);
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivityAndCollapse(home);
            } catch (RuntimeException ignored) {
            }
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::refresh, 400L);
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Oh My CPA");
        t.setState(FloatService.isShowing() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (android.os.Build.VERSION.SDK_INT >= 29) t.setSubtitle(FloatService.isShowing() ? "悬浮窗已开" : "点按打开悬浮窗");
        t.updateTile();
    }
}
