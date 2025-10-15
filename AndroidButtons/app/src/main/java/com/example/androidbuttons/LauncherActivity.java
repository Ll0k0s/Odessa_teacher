package com.example.androidbuttons;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

/**
 * Вспомогательная "пустая" активити, которая стартует сервис оверлея и сразу закрывается.
 * Показываем главный экран только если требуется запросить разрешение или запуск сделан из оверлея.
 */
public class LauncherActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!canDrawOverlays()) {
            displayOverlayPermissionSettings();
            return;
        }

        startOverlayService();
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mainIntent.putExtra(MainActivity.EXTRA_HIDE_AFTER_BOOT, true);
        startActivity(mainIntent);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void displayOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void startOverlayService() {
        Context appCtx = getApplicationContext();
        Intent svcIntent = new Intent(appCtx, FloatingOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appCtx.startForegroundService(svcIntent);
        } else {
            appCtx.startService(svcIntent);
        }
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(this);
    }
}
