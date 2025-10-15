package com.example.androidbuttons;

import android.graphics.Rect;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;

import androidx.appcompat.app.AppCompatActivity;

import com.example.androidbuttons.databinding.ActivitySettingsBinding;

/**
 * Экран настроек: позволяет выбрать IP/порт TCP сервера и локомотив.
 * Также отображает живой лог и индикаторы подключения. Комментарии ниже поясняют тонкости
 * управления полями и межпоточных взаимодействий.
 */
public class SettingsActivity extends AppCompatActivity {
    private ActivitySettingsBinding binding;
    private final java.util.Timer timer = new java.util.Timer("settings-console", true);
    private final StringBuilder consoleRemainder = new StringBuilder();
    private final java.util.Timer statusTimer = new java.util.Timer("settings-status", true);
    private android.content.SharedPreferences prefs;

    private String pendingHost;
    private String pendingPort;
    private String pendingOverlayX;
    private String pendingOverlayY;
    private String pendingOverlayScale;
    private boolean pendingDirty = false;
    private boolean suppressWatchers = false;
    private boolean keyboardVisible = false;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardListener;
    private android.content.BroadcastReceiver overlayUpdateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());


        // Консоль делаем прокручиваемой
        binding.textConsole.setMovementMethod(new ScrollingMovementMethod());

        // Инициализируем поля из SharedPreferences
        prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
        refreshValuesFromPreferences();

        // Сохраняем изменения полей только после скрытия клавиатуры
        // Для каждого текстового поля используем текстовые вотчеры, которые просто помечают
        // изменённые значения. Фактическая запись в SharedPreferences откладывается до момента,
        // когда пользователь покидает поле (скрывается клавиатура) или экран.
        binding.valueAddrTCP.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingHost = String.valueOf(s);
                pendingDirty = true;
            }
        });
        binding.valuePortTCP.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingPort = String.valueOf(s);
                pendingDirty = true;
            }
        });

        // Overlay X coordinate
        binding.valueOverlayX.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingOverlayX = String.valueOf(s);
                pendingDirty = true;
                
                // Применяем координату X немедленно
                Integer xValue = parseIntSafe(pendingOverlayX, -10000, 10000);
                if (xValue != null) {
                    xValue = eliminateTinyOffset(xValue);
                    prefs.edit().putInt(AppState.KEY_OVERLAY_X, xValue).apply();
                    // Отправляем broadcast для немедленного применения
                    android.content.Intent intent = new android.content.Intent("com.example.androidbuttons.APPLY_POSITION_NOW");
                    intent.putExtra("x", xValue);
                    sendBroadcast(intent);
                }
            }
        });

        // Overlay Y coordinate
        binding.valueOverlayY.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingOverlayY = String.valueOf(s);
                pendingDirty = true;
                
                // Применяем координату Y немедленно
                Integer yValue = parseIntSafe(pendingOverlayY, -10000, 10000);
                if (yValue != null) {
                    prefs.edit().putInt(AppState.KEY_OVERLAY_Y, yValue).apply();
                    // Отправляем broadcast для немедленного применения
                    android.content.Intent intent = new android.content.Intent("com.example.androidbuttons.APPLY_POSITION_NOW");
                    intent.putExtra("y", yValue);
                    sendBroadcast(intent);
                }
            }
        });

        // SeekBar для overlay scale
        binding.seekbarOverlayScale.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float scale = 0.1f + (progress * 0.05f);
                binding.textScaleValue.setText(String.format(java.util.Locale.US, "%.2f", scale));
                if (fromUser) {
                    pendingOverlayScale = String.valueOf(scale);
                    pendingDirty = true;
                    // Применяем масштаб в реальном времени через broadcast
                    prefs.edit().putFloat(AppState.KEY_OVERLAY_SCALE, scale).apply();
                    // Отправляем broadcast для немедленного применения масштаба
                    android.content.Intent intent = new android.content.Intent("com.example.androidbuttons.APPLY_SCALE_NOW");
                    intent.putExtra("scale", scale);
                    sendBroadcast(intent);
                    android.util.Log.d("SettingsActivity", "SeekBar changed scale to: " + scale);
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                // Ничего не делаем
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                // Масштаб уже применяется в реальном времени через onProgressChanged
                // Перезапуск сервиса НЕ нужен, т.к. broadcast уже отправлен
            }
        });

        // Наполняем spinner значениями Loco1..Loco8
        String[] locoItems = new String[8];
        for (int i = 0; i < 8; i++) locoItems[i] = "Loco" + (i + 1);
        ArrayAdapter<String> locoAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                locoItems
        );
        locoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerNum.setAdapter(locoAdapter);
        binding.spinnerNum.setSelection(Math.max(0, AppState.selectedLoco.get() - 1));
        // При выборе нового локомотива сразу обновляем глобальное состояние. Это решение мгновенно
        // влияет на MainActivity, которая читает AppState.selectedLoco при отправке команд.
        binding.spinnerNum.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AppState.selectedLoco.set(position + 1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { /* keep prev */ }
        });

        // Переключатель разрешения изменения overlay окна
        binding.switchAllowOverlayModification.setOnCheckedChangeListener((buttonView, isChecked) -> {
            android.util.Log.e("SettingsActivity", "═══════ SWITCH CLICKED: isChecked=" + isChecked + " ═══════");
            
            prefs.edit()
                    .putBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, isChecked)
                    .commit(); // ИЗМЕНЕНО НА COMMIT для гарантии немедленного сохранения!
            
            // ПРОВЕРЯЕМ что сохранилось
            boolean saved = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
            android.util.Log.e("SettingsActivity", "═══════ SAVED VALUE: " + saved + " ═══════");
            
            String status = isChecked ? "разрешено" : "запрещено";
            android.widget.Toast.makeText(this, "Изменение окна " + status, android.widget.Toast.LENGTH_SHORT).show();

            // Включаем / отключаем элементы управления сразу
            applyEditModeEnabled(isChecked);

			// При включении режима редактирования — выставляем зелёный (1) по умолчанию
			if (isChecked) {
				StateBus.publishStripState(1);
			}
        });

        // Загружаем состояние переключателя
        binding.switchAllowOverlayModification.setChecked(
                prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true)
        );
    // Применяем состояние edit-mode к контролам
    applyEditModeEnabled(binding.switchAllowOverlayModification.isChecked());

    // Слушатель внешних изменений (если флаг изменён из другого места)
    prefs.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
        if (AppState.KEY_OVERLAY_ALLOW_MODIFICATION.equals(key)) {
            boolean allow = sharedPreferences.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
            if (binding.switchAllowOverlayModification.isChecked() != allow) {
                binding.switchAllowOverlayModification.setChecked(allow);
            }
            applyEditModeEnabled(allow);
        }
    });

        // Периодически сливаем очередь лога в text_console
        timer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                while (!AppState.consoleQueue.isEmpty()) {
                    String s = AppState.consoleQueue.poll();
                    if (s == null) break;
                    sb.append(s);
                }
                if (sb.length() > 0) {
                    String out = sb.toString();
                    runOnUiThread(() -> appendColored(out));
                }
            }
        }, 200, 200);

        // Обновление индикаторов статуса TCP
        // Индикаторы подключения обновляем каждые 100мс для быстрого отклика на разрыв соединения
        statusTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override public void run() {
                runOnUiThread(() -> {
                    boolean wasReachable = binding.switchTCPIndicator.isChecked();
                    boolean isReachable = AppState.tcpReachable;

                    // Обновляем индикатор
                    binding.switchTCPIndicator.setChecked(isReachable);
                    binding.progressBarTCPIndicator.setVisibility(AppState.tcpConnecting ? View.VISIBLE : View.GONE);
                    
                    // Логируем изменение статуса
                    if (wasReachable != isReachable) {
                        android.util.Log.d("SettingsActivity", "TCP reachability changed: " + wasReachable + " -> " + isReachable + 
                                " (connecting=" + AppState.tcpConnecting + ")");
                    }
                });
            }
        }, 0, 100);

        setupKeyboardListener();
        setupOverlayUpdateReceiver();
    }

    /**
     * Настраивает BroadcastReceiver для получения обновлений позиции и масштаба overlay окна
     * в реальном времени при изменении через жесты.
     */
    private void setupOverlayUpdateReceiver() {
        overlayUpdateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (AppState.ACTION_OVERLAY_UPDATED.equals(intent.getAction())) {
                    // Обновляем поля координат, если они были переданы
                    if (intent.hasExtra(AppState.KEY_OVERLAY_X)) {
                        int x = intent.getIntExtra(AppState.KEY_OVERLAY_X, 0);
                        pendingOverlayX = String.valueOf(x);
                        suppressWatchers = true;
                        updateField(binding.valueOverlayX, pendingOverlayX);
                        suppressWatchers = false;
                        android.util.Log.d("SettingsActivity", "Overlay X updated from broadcast: " + x);
                    }
                    
                    if (intent.hasExtra(AppState.KEY_OVERLAY_Y)) {
                        int y = intent.getIntExtra(AppState.KEY_OVERLAY_Y, 0);
                        pendingOverlayY = String.valueOf(y);
                        suppressWatchers = true;
                        updateField(binding.valueOverlayY, pendingOverlayY);
                        suppressWatchers = false;
                        android.util.Log.d("SettingsActivity", "Overlay Y updated from broadcast: " + y);
                    }
                    
                    // Обновляем масштаб, если он был передан
                    if (intent.hasExtra(AppState.KEY_OVERLAY_SCALE)) {
                        float scale = intent.getFloatExtra(AppState.KEY_OVERLAY_SCALE, 1.0f);
                        pendingOverlayScale = String.valueOf(scale);
                        int progress = (int) Math.round((scale - 0.1f) / 0.05f);
                        binding.seekbarOverlayScale.setProgress(progress);
                        binding.textScaleValue.setText(String.format(java.util.Locale.US, "%.2f", scale));
                        android.util.Log.d("SettingsActivity", "Overlay scale updated from broadcast: " + scale);
                    }
                }
            }
        };
        
        android.content.IntentFilter filter = new android.content.IntentFilter(AppState.ACTION_OVERLAY_UPDATED);
        registerReceiver(overlayUpdateReceiver, filter);
    }

    /**
     * Активация / деактивация элементов UI для редактирования overlay (позиция, масштаб).
     * В режиме редактирования (allow=true) — поля активны, иначе отключены.
     */
    private void applyEditModeEnabled(boolean allow) {
        // Поля координат и масштаб
        binding.valueOverlayX.setEnabled(allow);
        binding.valueOverlayY.setEnabled(allow);
        binding.seekbarOverlayScale.setEnabled(allow);
        // Визуальная подсказка (прозрачность полей если выключено)
        float alpha = allow ? 1f : 0.4f;
        binding.valueOverlayX.setAlpha(alpha);
        binding.valueOverlayY.setAlpha(alpha);
        binding.seekbarOverlayScale.setAlpha(alpha);
        binding.textScaleValue.setAlpha(alpha);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Не вызываем refreshValuesFromPreferences(), чтобы не затирать введённые пользователем значения
        // Значения загружаются только один раз в onCreate()
        android.util.Log.d("SettingsActivity", "onResume() called. Current pending values: " +
                "X=" + pendingOverlayX + " Y=" + pendingOverlayY + 
                " Scale=" + pendingOverlayScale);
        // Removed alpha broadcast code
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timer.cancel();
        statusTimer.cancel();
        if (keyboardListener != null) {
            View root = binding.getRoot();
            root.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardListener);
            keyboardListener = null;
        }
        if (overlayUpdateReceiver != null) {
            unregisterReceiver(overlayUpdateReceiver);
            overlayUpdateReceiver = null;
        }
    }

	@Override
	protected void onPause() {
		super.onPause();
		android.util.Log.d("SettingsActivity", "onPause() called. Saving pending values: " +
				"X=" + pendingOverlayX + " Y=" + pendingOverlayY + 
				" Scale=" + pendingOverlayScale);
		applyPendingChanges(true);
        // Removed alpha broadcast code
        }

        /**
     * Добавляет строки из лога в текстовое поле с подсветкой префиксов. Реализация учитывает, что
     * данные приходят пачками; поэтому храним «хвост» без переводов строк и дорисовываем его при
     * следующем вызове.
     */
    private void appendColored(String text) {
        consoleRemainder.append(text);
        int idx;
        while ((idx = indexOfNewline(consoleRemainder)) >= 0) {
            String line = consoleRemainder.substring(0, idx + 1);
            consoleRemainder.delete(0, idx + 1);

            int color = -1;
            int removeLen = 0;
            if (line.startsWith("[UART→]")) {
                color = 0xFF90EE90; removeLen = "[UART→]".length();
            } else if (line.startsWith("[UART←]")) {
                color = 0xFF006400; removeLen = "[UART←]".length();
            } else if (line.startsWith("[#TCP_TX#]")) {
                color = 0xFF87CEFA; removeLen = "[#TCP_TX#]".length();
            } else if (line.startsWith("[#TCP_RX#]")) {
                color = 0xFF0000FF; removeLen = "[#TCP_RX#]".length();
            }

            if (removeLen > 0 && removeLen <= line.length()) {
                line = line.substring(removeLen);
            }
            if (color != -1) {
                SpannableString ss = new SpannableString(line);
                ss.setSpan(new ForegroundColorSpan(color), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                binding.textConsole.append(ss);
            } else {
                binding.textConsole.append(line);
            }
        }
        int scrollAmount = binding.textConsole.getLayout() != null
                ? binding.textConsole.getLayout().getLineTop(binding.textConsole.getLineCount()) - binding.textConsole.getHeight()
                : 0;
        if (scrollAmount > 0) binding.textConsole.scrollTo(0, scrollAmount);
    }

    private static int indexOfNewline(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') return i;
        }
        return -1;
    }

    /**
     * Отслеживаем появление/скрытие клавиатуры. Как только клавиатура скрывается — применяем
     * изменения (если они были), чтобы не хранить несохранённые значения.
     */
    private void setupKeyboardListener() {
        View root = binding.getRoot();
        keyboardListener = () -> {
            Rect r = new Rect();
            root.getWindowVisibleDisplayFrame(r);
            int screenHeight = root.getRootView().getHeight();
            int keyboardHeight = screenHeight - r.bottom;
            boolean visible = keyboardHeight > screenHeight * 0.15f;
            if (keyboardVisible != visible) {
                keyboardVisible = visible;
                android.util.Log.d("SettingsActivity", "Keyboard visibility changed: " + visible + 
                        ". Current scale: " + pendingOverlayScale);
                if (!visible) {
                    applyPendingChanges(false);
                }
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);
    }

    /**
     * Перечитывает актуальные значения из SharedPreferences и заполняет поля ввода без срабатывания
     * вотчеров. Также сбрасывает флаги «грязности» pending-полей.
     */
    private void refreshValuesFromPreferences() {
        String host = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        int port = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
    int overlayX = eliminateTinyOffset(prefs.getInt(AppState.KEY_OVERLAY_X, 0));
    int overlayY = prefs.getInt(AppState.KEY_OVERLAY_Y, 0);
        float overlayScale = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f);

        pendingHost = host;
        pendingPort = String.valueOf(port);
        pendingOverlayX = String.valueOf(overlayX);
        pendingOverlayY = String.valueOf(overlayY);
        pendingOverlayScale = String.valueOf(overlayScale);

        android.util.Log.d("SettingsActivity", "Loaded overlay params: X=" + overlayX + " Y=" + overlayY + 
                " Scale=" + overlayScale);

        updateField(binding.valueAddrTCP, pendingHost);
        updateField(binding.valuePortTCP, pendingPort);
        updateField(binding.valueOverlayX, pendingOverlayX);
        updateField(binding.valueOverlayY, pendingOverlayY);
        
        // Обновляем SeekBar и TextView для масштаба
        int progress = (int) Math.round((overlayScale - 0.1f) / 0.05f);
        binding.seekbarOverlayScale.setProgress(progress);
        binding.textScaleValue.setText(String.format(java.util.Locale.US, "%.2f", overlayScale));
        
        pendingDirty = false;
    }

    /**
     * Сохраняет накопленные изменения в SharedPreferences. Валидация порта/scale не даёт
     * записать некорректные значения; если поле пустое — оставляем прежнее.
     *
     * @param force true, если нужно сохранить даже без флага pendingDirty (например, при паузе).
     */
    private void applyPendingChanges(boolean force) {
        if (!force && !pendingDirty) {
            return;
        }

        String hostValue = pendingHost != null ? pendingHost.trim() : "";
        if (hostValue.isEmpty()) {
            hostValue = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        }

        Integer portValue = parseIntSafe(pendingPort, 1, 65535);
        if (portValue == null) {
            portValue = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
        }

        // Overlay координаты и масштаб
        Integer overlayXValue = parseIntSafe(pendingOverlayX, -10000, 10000);
        if (overlayXValue == null) {
            overlayXValue = prefs.getInt(AppState.KEY_OVERLAY_X, 0);
        }
        overlayXValue = eliminateTinyOffset(overlayXValue);

        Integer overlayYValue = parseIntSafe(pendingOverlayY, -10000, 10000);
        if (overlayYValue == null) {
            overlayYValue = prefs.getInt(AppState.KEY_OVERLAY_Y, 0);
        }

        Float overlayScaleValue = parseFloatSafe(pendingOverlayScale, 0.1f, 5.0f);
        if (overlayScaleValue == null) {
            overlayScaleValue = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f);
        }

        boolean changed = false;
        android.content.SharedPreferences.Editor editor = prefs.edit();
        if (!hostValue.equals(prefs.getString(AppState.KEY_TCP_HOST, ""))) {
            editor.putString(AppState.KEY_TCP_HOST, hostValue);
            changed = true;
        }
        if (portValue != prefs.getInt(AppState.KEY_TCP_PORT, 9000)) {
            editor.putInt(AppState.KEY_TCP_PORT, portValue);
            changed = true;
        }
        if (overlayXValue != prefs.getInt(AppState.KEY_OVERLAY_X, 0)) {
            editor.putInt(AppState.KEY_OVERLAY_X, overlayXValue);
            changed = true;
        }
        if (overlayYValue != prefs.getInt(AppState.KEY_OVERLAY_Y, 0)) {
            editor.putInt(AppState.KEY_OVERLAY_Y, overlayYValue);
            changed = true;
        }
        if (Math.abs(overlayScaleValue - prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f)) > 0.001f) {
            editor.putFloat(AppState.KEY_OVERLAY_SCALE, overlayScaleValue);
            changed = true;
        }
        if (changed) {
            editor.apply();
            android.util.Log.d("SettingsActivity", "Saved overlay params: X=" + overlayXValue + " Y=" + overlayYValue + 
                    " Scale=" + overlayScaleValue);
        }

        pendingHost = hostValue;
        pendingPort = String.valueOf(portValue);
        pendingOverlayX = String.valueOf(overlayXValue);
        pendingOverlayY = String.valueOf(overlayYValue);
        pendingOverlayScale = String.valueOf(overlayScaleValue);
        pendingDirty = false;

        updateField(binding.valueAddrTCP, pendingHost);
        updateField(binding.valuePortTCP, pendingPort);
        updateField(binding.valueOverlayX, pendingOverlayX);
        updateField(binding.valueOverlayY, pendingOverlayY);
        
        // Обновляем SeekBar и TextView для масштаба
        int progress = (int) Math.round((overlayScaleValue - 0.1f) / 0.05f);
        binding.seekbarOverlayScale.setProgress(progress);
        binding.textScaleValue.setText(String.format(java.util.Locale.US, "%.2f", overlayScaleValue));
    }

    /**
     * Безопасно парсит целое число в заданных пределах. Возвращает null, если строка пустая или
     * выходит за рамки — в этом случае используем предыдущее значение.
     */
    private Integer parseIntSafe(String value, int min, int max) {
        if (value == null) return null;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int eliminateTinyOffset(int valuePx) {
        return Math.abs(valuePx) <= 12 ? 0 : valuePx;
    }
    /**
     * Безопасно парсит float в заданных пределах. Возвращает null, если строка пустая или
     * выходит за рамки — в этом случае используем предыдущее значение.
     */
    private Float parseFloatSafe(String value, float min, float max) {
        if (value == null) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (parsed < min || parsed > max) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Обновляет текстовое поле, временно отключая вотчеры, чтобы не выставлять лишний pendingDirty.
     */
    private void updateField(android.widget.EditText field, String value) {
        String target = value != null ? value : "";
        String current = field.getText() != null ? field.getText().toString() : "";
        if (current.equals(target)) {
            return;
        }
        suppressWatchers = true;
        field.setText(target);
        field.setSelection(target.length());
        suppressWatchers = false;
    }

    /**
     * Перезапускает FloatingOverlayService для применения новых настроек позиции и размера.
     */
    private void restartOverlayService() {
        android.content.Intent recreateIntent = new android.content.Intent(this, FloatingOverlayService.class);
        recreateIntent.setAction(FloatingOverlayService.ACTION_RECREATE_OVERLAY);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(recreateIntent);
        } else {
            startService(recreateIntent);
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }
}
