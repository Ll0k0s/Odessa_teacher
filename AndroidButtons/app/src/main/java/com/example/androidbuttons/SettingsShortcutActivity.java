package com.example.androidbuttons;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Отдельный ярлык, который открывает экран настроек и при этом обеспечивает запуск сервиса оверлея.
 */
public class SettingsShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startOverlayService();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true);
        startActivity(intent);
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
}
