package com.example.androidbuttons;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.Nullable;

/**
 * Главный экран приложения. Управляет соединениями (TCP/USB), запускает overlay-сервис,
 * отправляет команды и синхронизирует UI-состояние. Комментарии ниже объясняют цепочки событий.
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_SETTINGS = "com.example.androidbuttons.EXTRA_OPEN_SETTINGS";
    public static final String EXTRA_HIDE_AFTER_BOOT = "com.example.androidbuttons.EXTRA_HIDE_AFTER_BOOT";

    private static final String TAG_SERVICE = "MainActivitySvc";
    private static final int REQUEST_OVERLAY_PERMISSION = 1001;

    private TcpManager tcpManager;
    private DataBuffer uiBuffer;
    private SharedPreferences prefs;
    private ActivityResultLauncher<Intent> settingsLauncher;
    private boolean overlayPermissionRequested = false;
    private int currentState = 0;
    private boolean settingsLaunched = false;
    private java.util.Timer tcpStatusTimer;

    /**
     * Слушатель, которым overlay-сервис сообщает о выборе состояния пользователем. Все действия
     * выполняем на UI-потоке, чтобы гарантировать корректное взаимодействие с виджетами/менеджерами.
     */
    private final StateBus.OverlaySelectionListener overlaySelectionListener = state ->
        runOnUiThread(() -> handleOverlaySelection(state));

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sharedPrefs, key) -> {
        if (sharedPrefs == null || key == null) {
            return;
        }
        if (AppState.KEY_TCP_HOST.equals(key) || AppState.KEY_TCP_PORT.equals(key)) {
            String host = sharedPrefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
            int port = sharedPrefs.getInt(AppState.KEY_TCP_PORT, 9000);
            if (host != null) {
                host = host.trim();
            }
            tcpManager.disableAutoConnect();
            tcpManager.disconnect();
            tcpManager.enableAutoConnect(host, port);
            tcpManager.connect(host, port);
        }
    };



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Launcher для открытия SettingsActivity. После возврата сворачиваем главную активити,
        // чтобы повторно попасть сюда только через иконку или системное «Недавние».
        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    settingsLaunched = false;
                    if (!isFinishing()) {
                        moveTaskToBack(true);
                    }
                }
        );

        uiBuffer = new DataBuffer(256, data -> AppState.consoleQueue.offer(data));

        // Инициализация TCP-менеджера. Колбэки приводим к UI-потоку, чтобы обновлять глобальные флаги
        // и лог. Основная логика — реагировать только на сообщения, относящиеся к выбранному локомотиву.
        tcpManager = new TcpManager(
                () -> runOnUiThread(() -> {
                    AppState.tcpConnecting = true;
                }),
                () -> runOnUiThread(() -> {
                    AppState.tcpConnecting = false;
                }),
                data -> {
                    if (data == null || data.isEmpty()) {
                        return;
                    }
                    String[] lines = data.split("\n");
                    int locoTarget = AppState.selectedLoco.get();
                    for (String line : lines) {
                        if (line == null) {
                            continue;
                        }
                        String ln = line.trim();
                        if (ln.isEmpty()) {
                            continue;
                        }
                        int locoVal = extractDecimal(ln, "loco=");
                        if (locoVal <= 0 || locoVal != locoTarget) {
                            continue;
                        }
                        int stateVal = extractDecimal(ln, "state=");
                        if (stateVal < 1 || stateVal > 6) {
                            continue;
                        }

                        uiBuffer.offer("[#TCP_RX#]" + "Rx: loco" + locoVal + " -> state" + stateVal + "\n");

                        final int stateCopy = stateVal;
                        runOnUiThread(() -> {
                            updateStateFromExternal(stateCopy);
                        });
                    }
                },
                error -> {
                    // suppress UI noise
                },
                status -> runOnUiThread(() -> {
                    boolean connected = "connected".equals(status);
                    AppState.tcpConnected = connected;
                    AppState.tcpReachable = connected;
                    Log.d("MainActivity", "TCP status changed: " + status + " -> connected=" + connected);
                })
        );




        prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        String initHost = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        int initPort = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
        tcpManager.enableAutoConnect(initHost, initPort);
        
        // Устанавливаем начальное состояние = 1 (зелёный светофор) при запуске
        updateStateFromExternal(1);
        StateBus.publishOverlaySelection(1);
        Log.d("MainActivity", "Initial state set to 1 (green)");

    // Стартовая быстрая проверка (фоновая)
    runOffUi(() -> performTcpHealthCheck("init"));

        // Запускаем периодическую проверку TCP статуса каждую секунду
        tcpStatusTimer = new java.util.Timer();
        tcpStatusTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                performTcpHealthCheck("tick");
            }
        }, 1000, 1000); // Проверяем каждую 1 секунду после первой секунды
    }

    private void performTcpHealthCheck(String phase) {
        // Никогда не выполняем потенциально сетевую часть на UI потоке
        if (android.os.Looper.getMainLooper().isCurrentThread()) {
            runOffUi(() -> performTcpHealthCheck(phase + "_bg"));
            return;
        }
        boolean connectionAlive = tcpManager.checkConnectionAlive();
        boolean wasConnected = AppState.tcpConnected;

        // Всегда делаем probe независимо от флага connectionAlive, чтобы обнаружить "зомби" сокет
        boolean endpointReachable = tcpManager.isEndpointReachable(600);
        // Если сокет считался живым, но probe упал — считаем соединение потерянным
        if (connectionAlive && !endpointReachable) {
            Log.w("MainActivity", "Zombie TCP socket detected (socket alive, probe failed). Forcing disconnect");
            connectionAlive = false;
            runOnUiThread(() -> tcpManager.disconnect());
        }
        boolean wasReachable = AppState.tcpReachable;

        String host = tcpManager.getTargetHost();
        int port = tcpManager.getTargetPort();

        if (wasConnected != connectionAlive) {
            Log.w("MainActivity", "TCP connection state changed: " + wasConnected + " -> " + connectionAlive +
                    " (phase=" + phase + ")");
            if (!connectionAlive && wasConnected) {
                runOnUiThread(() -> {
                    tcpManager.disconnect();
                    Log.e("MainActivity", "Connection lost, forcing disconnect (phase=" + phase + ")");
                });
            }
        }

        if (wasReachable != endpointReachable) {
            Log.w("MainActivity", "TCP reachability changed: " + wasReachable + " -> " + endpointReachable +
                    " (phase=" + phase + ")");
        }

        AppState.tcpConnected = connectionAlive;
        AppState.tcpReachable = endpointReachable;

    }

    // Простой вспомогательный метод для запуска фоновых задач без создания лишних исполнителей
    private void runOffUi(Runnable r) {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(r);
    }

    // Упрощенный health-check: публикация в консоль отключена по запросу пользователя

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Останавливаем таймер проверки TCP статуса
        if (tcpStatusTimer != null) {
            tcpStatusTimer.cancel();
            tcpStatusTimer = null;
        }
        
        tcpManager.disableAutoConnect();
        tcpManager.disconnect();
        if (prefs != null) {
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            } catch (Exception ignored) {
                // no-op
            }
        }
        uiBuffer.close();
        
        // Останавливаем overlay-сервис при закрытии активити
        try {
            Intent svcIntent = new Intent(this, FloatingOverlayService.class);
            stopService(svcIntent);
        } catch (Exception ignored) {
            // no-op
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (prefs == null) {
            prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
        }

        String host = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        int port = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
        tcpManager.updateTarget(host, port);

        ensureOverlayServiceRunning();
        StateBus.registerSelectionListener(overlaySelectionListener);
        if (currentState > 0) {
            StateBus.publishStripState(currentState);
        }

        processLaunchFlags(getIntent(), false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        StateBus.unregisterSelectionListener(overlaySelectionListener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        processLaunchFlags(intent, true);
    }

    /**
     * Обрабатывает флаги запуска (от системных ресиверов/shortcut). Позволяет открыть настройки или
     * отправить приложение в фон сразу после старта.
     */
    private void processLaunchFlags(@Nullable Intent sourceIntent, boolean fromNewIntent) {
        if (sourceIntent == null) {
            return;
        }
        boolean openRequested = sourceIntent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false);
        boolean hideRequested = sourceIntent.getBooleanExtra(EXTRA_HIDE_AFTER_BOOT, false);

        if (openRequested) {
            sourceIntent.removeExtra(EXTRA_OPEN_SETTINGS);
            settingsLaunched = true;
            launchSettingsActivity();
            return;
        }

        if (hideRequested) {
            sourceIntent.removeExtra(EXTRA_HIDE_AFTER_BOOT);
            settingsLaunched = false;
            moveTaskToBack(true);
            return;
        }

        if (!fromNewIntent && !settingsLaunched) {
            settingsLaunched = true;
            launchSettingsActivity();
        }
    }

    /**
     * Стартует SettingsActivity с отключенной анимацией перехода.
     */
    private void launchSettingsActivity() {
        Intent intent = new Intent(this, SettingsActivity.class);
        if (settingsLauncher != null) {
            settingsLauncher.launch(intent);
        } else {
            startActivity(intent);
        }
        overridePendingTransition(0, 0);
    }

    /**
     * Реагирует на выбор состояния пользователем в overlay: применяет новый режим и триггерит
     * отправку команд.
     */
    private void handleOverlaySelection(int state) {
        if (state < 1 || state > 5) {
            return;
        }
        applyStripState(state, true);
    }

    /**
     * Главный метод смены состояния: обновляет локальное значение, при необходимости рассылку
     * команды и уведомление StateBus. Используется как локальными, так и внешними событиями.
     */
    private void applyStripState(int state, boolean sendCommands) {
        if (state < 1 || state > 5) {
            return;
        }
        // Блокируем смену состояния, если включён режим редактирования overlay
        boolean allowModification = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
        if (allowModification) {
            Log.d(TAG_SERVICE, "applyStripState ignored in edit mode state=" + state);
            return;
        }
        currentState = state;
        if (sendCommands) {
            sendExclusiveRelays(state);
        }
        StateBus.publishStripState(state);
    }

    /**
     * Обновляет состояние полосы в ответ на внешние сигналы (TCP/UART). Не инициирует повторную
     * отправку, чтобы избежать циклов.
     */
    private void updateStateFromExternal(int state) {
        if (state < 1 || state > 5) {
            return;
        }
        if (state == currentState) {
            return;
        }
        boolean allowModification = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
        if (allowModification) {
            Log.d(TAG_SERVICE, "External state update ignored (edit mode) state=" + state);
            return;
        }
        currentState = state;
        StateBus.publishStripState(state);
    }

    /**
     * Вытаскивает положительное целое после заданного токена (например, "loco=" или "state=").
     * Возвращает -1, если токен отсутствует или значение не удалось считать.
     */
    private int extractDecimal(String line, String token) {
        if (line == null || token == null) {
            return -1;
        }
        int idx = line.indexOf(token);
        if (idx < 0) {
            return -1;
        }
        int pos = idx + token.length();
        int value = 0;
        boolean has = false;
        while (pos < line.length()) {
            char c = line.charAt(pos);
            if (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                has = true;
                pos++;
            } else {
                break;
            }
        }
        return has ? value : -1;
    }

    /**
     * Проверяет разрешение на overlay и запускает сервис, если его ещё нет. При необходимости
     * запрашивает разрешение у пользователя.
     */
    private void ensureOverlayServiceRunning() {
        try {
            Context appCtx = getApplicationContext();
            if (!canDrawOverlays()) {
                Log.w(TAG_SERVICE, "Overlay permission missing, requesting");
                requestOverlayPermission();
                return;
            }

            overlayPermissionRequested = false;

            Intent svcIntent = new Intent(appCtx, FloatingOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(svcIntent);
            } else {
                appCtx.startService(svcIntent);
            }
            Log.i(TAG_SERVICE, "FloatingOverlayService start requested");
        } catch (Exception ex) {
            Log.e(TAG_SERVICE, "Failed to start FloatingOverlayService", ex);
        }
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(this);
    }

    /**
     * Запускает системный экран для выдачи разрешения на overlay.
     */
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        if (overlayPermissionRequested) {
            Log.i(TAG_SERVICE, "Overlay permission dialog already shown");
            return;
        }
        overlayPermissionRequested = true;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            Log.i(TAG_SERVICE, "Requesting overlay permission via settings");
        } catch (ActivityNotFoundException ex) {
            overlayPermissionRequested = false;
            Log.e(TAG_SERVICE, "Overlay permission settings unavailable", ex);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            overlayPermissionRequested = false;
            if (canDrawOverlays()) {
                Log.i(TAG_SERVICE, "Overlay permission granted");
                ensureOverlayServiceRunning();
            } else {
                Log.w(TAG_SERVICE, "Overlay permission still missing after user interaction");
            }
        }
    }

    /**
     * Отправляет команду «эксклюзивного состояния»: один байт состояния для выбранного локомотива.
     * Команда отправляется в TCP и протоколируется в консоли.
     */
    private void sendExclusiveRelays(int active) {
        int loco = AppState.selectedLoco.get();
        int state = Math.max(1, Math.min(6, active));
        tcpManager.sendControl(loco, state);
        if (tcpManager.connectionActive()) {
            uiBuffer.offer("[#TCP_TX#]" + "Tx: loco" + loco + " -> state" + state + "\n");
        }
    }
}
