package com.example.androidbuttons;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.appcompat.content.res.AppCompatResources;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground-сервис поверх всех экранов, показывающий диагностическую полоску.
 * Управляет отображением состояния, жестами перемещения/масштабирования и прозрачностью.
 */
public class FloatingOverlayService extends Service {

	private static final String TAG = "FloatingOverlayService";
	public static final String ACTION_RECREATE_OVERLAY = "com.example.androidbuttons.action.RECREATE_OVERLAY";
	private static final String CHANNEL_ID = "overlay_probe_channel";
	private static final int NOTIFICATION_ID = 1001;
	private static final long HEARTBEAT_INTERVAL_MS = 3000L;
	private static final long STRIP_ANIM_DURATION = 1000L;  // Увеличено для более плавной анимации

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final AtomicBoolean overlayAttached = new AtomicBoolean(false);

	// Listener для изменений preferences (ВАЖНО: хранить сильную ссылку, иначе GC удалит!)
	private android.content.SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

	private WindowManager windowManager;
	private View overlayView;
	private WindowManager.LayoutParams overlayParams;
	private FrameLayout overlayRoot;
	private ImageView overlayStateStrip;
	private ImageView overlayCrossfadeView;
	private ValueAnimator overlayAnimator;
	private int currentState = 0;
	private int lastResId = 0;

	// Отложенная стартовая альфа до момента создания overlayView
	private float pendingInitialAlpha = 1.0f;

	// Поля для отслеживания перемещения и изменения размера
	private float initialTouchX;
	private float initialTouchY;
	private int initialX;
	private int initialY;
	
	// Поля для pinch-to-zoom (масштабирование двумя пальцами)
	private float initialDistance = 0;
	private float initialScale = 1.0f;
	private boolean isScaling = false;
	private float scalePivotX, scalePivotY; // Центр масштабирования на экране
	private int initialWidth, initialHeight; // Размеры окна в начале масштабирования
	private boolean animationPausedForScaling = false; // Флаг паузы анимации во время масштабирования
	// Дополнительно для сглаживания pinch-to-zoom
	private float lastDistanceDuringScale = 0f;
	private float smoothedScale = 1.0f;
	private long lastScaleTs = 0L;

	// Режим жеста: запрещаем одновременное перемещение и масштабирование
	private enum GestureMode { NONE, MOVE, SCALE }
	private GestureMode gestureMode = GestureMode.NONE;

	// Add new flags near other gesture fields
	private boolean didMoveDuringGesture = false; // tracks if any MOVE actually changed position
	private boolean suppressMoveUntilUp = false; // after scaling, disallow move until full finger release

	/**
	 * Слушатель событий от основной активити. Каждый вызов транслируем на главный поток, так как
	 * StateBus может прислать событие из произвольного потока.
	 */
	private final StateBus.StripStateListener stripStateListener = state ->
		mainHandler.post(() -> {
			android.content.SharedPreferences p = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
			boolean allowModification = p.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
			if (allowModification) {
				// Если уже что-то отображено — блокируем, иначе разрешаем первый paint
				if (currentState != 0) {
					Log.d(TAG, "stripStateListener: ignore state=" + state + " (edit mode, currentState=" + currentState + ")");
					return;
				} else {
					Log.d(TAG, "stripStateListener: first paint allowed in edit mode state=" + state);
				}
			}
			Log.d(TAG, "stripStateListener: applying state=" + state + " (current=" + currentState + ")");
			updateOverlayState(state);
		});

	private final Runnable heartbeatRunnable = new Runnable() {
		@Override
		public void run() {
			Log.d(TAG, "heartbeat attached=" + overlayAttached.get());
			refreshOverlayStatus();
			mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		Log.i(TAG, "onCreate");
		ensureChannel();
		windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
		if (windowManager == null) {
			Log.e(TAG, "WindowManager unavailable");
		}

		// Слушатель изменений SharedPreferences для автоматического применения прозрачности
		android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		
		// КРИТИЧЕСКИ ВАЖНО: сохраняем listener в поле класса, иначе GC удалит и он перестанет работать!
		preferenceListener = (sharedPreferences, key) -> {
			Log.e(TAG, "═══════ PREF CHANGED: key=" + key + " ═══════");
			if (AppState.KEY_OVERLAY_ALLOW_MODIFICATION.equals(key)) {
				boolean allow = sharedPreferences.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
				float targetAlpha = allow ? 0.7f : 1.0f;
				pendingInitialAlpha = targetAlpha;
				
				Log.e(TAG, "═══════ TARGET ALPHA=" + targetAlpha + " allow=" + allow + " overlayView=" + overlayView + " ═══════");
				
				// КРИТИЧЕСКИ ВАЖНО: применяем прозрачность немедленно через mainHandler
				mainHandler.post(() -> {
					if (overlayView != null) {
						overlayView.setAlpha(targetAlpha);
						Log.e(TAG, "✓✓✓ ALPHA CHANGED IMMEDIATELY -> " + targetAlpha + " (allow=" + allow + ")");
					} else {
						Log.e(TAG, "✗✗✗ overlayView is NULL, cannot change alpha!");
					}
				});
			}
		};
		prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
		// Применяем стартовую альфу согласно текущему флагу
		boolean allowAtStart = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
		pendingInitialAlpha = allowAtStart ? 0.7f : 1.0f;
		Log.d(TAG, "Initial overlay alpha based on allowModification=" + allowAtStart + " -> " + pendingInitialAlpha);
		
        // Регистрируем receiver для немедленного применения масштаба
        android.content.BroadcastReceiver scaleReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if ("com.example.androidbuttons.APPLY_SCALE_NOW".equals(intent.getAction())) {
                    float scale = intent.getFloatExtra("scale", 1.0f);
                    applyScale(scale);
                    Log.d(TAG, "Scale applied immediately: " + scale);
                }
            }
        };
        android.content.IntentFilter scaleFilter = new android.content.IntentFilter("com.example.androidbuttons.APPLY_SCALE_NOW");
        registerReceiver(scaleReceiver, scaleFilter);
        
        // Регистрируем receiver для немедленного применения позиции
        android.content.BroadcastReceiver positionReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if ("com.example.androidbuttons.APPLY_POSITION_NOW".equals(intent.getAction())) {
                    if (overlayParams == null || windowManager == null) return;
                    
                    if (intent.hasExtra("x")) {
                        int x = intent.getIntExtra("x", overlayParams.x);
                        int compensatedX = (x == 0) ? -computeLeftCompensation() : x;
                        overlayParams.x = compensatedX;
                    }
                    if (intent.hasExtra("y")) {
                        overlayParams.y = intent.getIntExtra("y", overlayParams.y);
                    }
                    
                    try {
                        windowManager.updateViewLayout(overlayView, overlayParams);
                        Log.d(TAG, "Position applied immediately: x=" + overlayParams.x + " y=" + overlayParams.y);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to apply position", e);
                    }
                }
            }
        };
        android.content.IntentFilter positionFilter = new android.content.IntentFilter("com.example.androidbuttons.APPLY_POSITION_NOW");
        registerReceiver(positionReceiver, positionFilter);
    }	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.i(TAG, "onStartCommand flags=" + flags + " startId=" + startId);
		Notification notification = buildNotification();
		startForeground(NOTIFICATION_ID, notification);

		if (intent != null && ACTION_RECREATE_OVERLAY.equals(intent.getAction())) {
			Log.i(TAG, "Recreating overlay with updated parameters");
			mainHandler.post(() -> {
				detachOverlay();
				maybeAttachOverlay();
			});
		} else {
			mainHandler.post(this::maybeAttachOverlay);
		}
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		Log.i(TAG, "onDestroy");
		mainHandler.removeCallbacks(heartbeatRunnable);
		detachOverlay();
		super.onDestroy();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	/**
	 * Пытаемся прикрепить overlay к WindowManager. Метод безопасно выходить, если уже прикреплены
	 * либо нет разрешения. Все операции оборачиваем в try/catch — WindowManager бросает исключения
	 * при изменении конфигурации/разрешений.
	 */
	private void maybeAttachOverlay() {
		if (overlayAttached.get()) {
			return;
		}
		if (windowManager == null) {
			Log.w(TAG, "maybeAttachOverlay: windowManager is null");
			return;
		}
		if (!canDrawOverlays()) {
			Log.w(TAG, "maybeAttachOverlay: overlay permission missing");
			return;
		}

		try {
			LayoutInflater inflater = LayoutInflater.from(this);
			overlayView = inflater.inflate(R.layout.overlay_probe, null, false);
			overlayRoot = overlayView.findViewById(R.id.overlayProbeRoot);
			if (overlayRoot == null && overlayView instanceof FrameLayout) {
				overlayRoot = (FrameLayout) overlayView;
			}
			overlayCrossfadeView = null;
			overlayAnimator = null;
		overlayParams = buildDefaultLayoutParams();
		windowManager.addView(overlayView, overlayParams);
		overlayAttached.set(true);

		// КРИТИЧНО: перечитываем актуальное значение разрешения ПРЯМО СЕЙЧАС
		android.content.SharedPreferences currentPrefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		boolean currentAllow = currentPrefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
		float actualAlpha = currentAllow ? 0.7f : 1.0f;
		pendingInitialAlpha = actualAlpha;
		
		Log.e(TAG, "▓▓▓ OVERLAY ATTACHED: actualAllow=" + currentAllow + " -> alpha=" + actualAlpha + " ▓▓▓");
		setOverlayAlpha(actualAlpha);			// --- ИНИЦИАЛИЗАЦИЯ СТАРТОВОГО СОСТОЯНИЯ ---
			// При активном режиме редактирования обычные внешние обновления состояния блокируются.
			// Однако при самом первом появлении overlay нам нужно что-то отрисовать (зелёный/1),
			// иначе пользователь увидит пустую прозрачную полоску. Поэтому:
			// 1. Проверяем есть ли уже глобально установленное состояние (StateBus.getCurrentState()).
			// 2. Если нет (0 или <0) — публикуем зелёный (1) через StateBus.publishStripState(1).
			// 3. Независимо от режима редактирования выполняем одноразовый прямой вызов updateOverlayState(existing)
			//    — stripStateListener пропустит первое обновление (currentState==0) и блокировку не наложит.
			int existing = StateBus.getCurrentState();
			if (existing <= 0) {
				StateBus.publishStripState(1); // фиксируем зелёный глобально и для других компонентов
				existing = 1;
			}
			updateOverlayState(existing);
			
			// Применяем скругления с текущим масштабом
			android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
			float currentScale = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f);
			updateCornerRadius(currentScale);
			
			Log.i(TAG, "Overlay attached");

			setupOverlayInteractions();
			refreshOverlayStatus();
			StateBus.registerStateListener(stripStateListener);
			mainHandler.removeCallbacks(heartbeatRunnable);
			mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
		} catch (RuntimeException ex) {
			Log.e(TAG, "Failed to attach overlay", ex);
			overlayAttached.set(false);
			overlayView = null;
		}
	}

	/**
	 * Настраивает обработчики жестов полосы для перемещения и изменения размера.
	 */
	private void setupOverlayInteractions() {
		if (overlayView == null) {
			return;
		}
		overlayStateStrip = overlayView.findViewById(R.id.overlayStateStrip);
		if (overlayStateStrip != null) {
			overlayStateStrip.setOnTouchListener(this::handleOverlayTouch);
		}
	}

	/**
	 * Обрабатывает касания по полосе и переводит их в условные зоны (1..5). Метод сейчас не
	 * используется, но хранится как готовая реализация для будущих итераций.
	 */
	private boolean handleStripTouch(MotionEvent event) {
		if (event == null || overlayStateStrip == null) {
			return false;
		}
		int action = event.getActionMasked();
		if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
			return action == MotionEvent.ACTION_UP;
		}
		int height = overlayStateStrip.getHeight();
		if (height <= 0) {
			return true;
		}
		float y = event.getY();
		int zone = (int) (y / (height / 5f)) + 1;
		if (zone < 1) zone = 1; else if (zone > 5) zone = 5;
		// Перед сменой состояния всегда проверяем флаг
		android.content.SharedPreferences p = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		boolean allowModification = p.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
		if (allowModification) {
			Log.d(TAG, "State tap ignored (edit mode) zone=" + zone);
			return true;
		}
		if (zone != currentState) {
			Log.i(TAG, "Overlay selects state=" + zone);
			updateOverlayState(zone);
			StateBus.publishOverlaySelection(zone);
		}
		return true;
	}

	/**
	 * Обрабатывает touch события для перемещения (1 палец) и масштабирования (2 пальца) overlay окна.
	 * Если в настройках отключено "Разрешить изменение окна", все касания игнорируются.
	 */
	private boolean handleOverlayTouch(View view, MotionEvent event) {
		if (overlayParams == null || windowManager == null) {
			return false;
		}

		// Проверяем, разрешено ли изменение окна
		android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		boolean allowModification = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
		
		if (!allowModification) {
			if (event.getAction() == MotionEvent.ACTION_UP) {
				return handleStripTouch(event);
			}
			return true;
		} else {
			// В режиме редактирования не вызываем смену состояния, но НЕ прерываем поток событий,
			// чтобы дошли до ACTION_UP и сохранили позицию/масштаб.
			if (event.getAction() == MotionEvent.ACTION_UP) {
				Log.d(TAG, "State change blocked in edit mode (tap ignored) — allowing gesture finalization");
			}
		}

		int action = event.getActionMasked();
		int pointerCount = event.getPointerCount();

		switch (action) {
			case MotionEvent.ACTION_DOWN:
				if (suppressMoveUntilUp && gestureMode == GestureMode.NONE) {
					Log.d(TAG, "ACTION_DOWN ignored (suppressMoveUntilUp still true) — waiting for full release previously? Resetting now");
				}
				// Если предыдущий жест pinch завершён (нет второго пальца и от прошлого ACTION_UP мы уже сбросили), гарантийно снимаем блокировку
				if (suppressMoveUntilUp) {
					Log.d(TAG, "Clearing suppressMoveUntilUp on new ACTION_DOWN");
					suppressMoveUntilUp = false;
				}
				initialTouchX = event.getRawX();
				initialTouchY = event.getRawY();
				initialX = overlayParams.x;
				initialY = overlayParams.y;
				gestureMode = GestureMode.MOVE;
				isScaling = false;
				didMoveDuringGesture = false; // reset
				// suppressMoveUntilUp уже очищён выше, если был
				return true;

			case MotionEvent.ACTION_POINTER_DOWN:
				if (pointerCount == 2 && gestureMode == GestureMode.MOVE) {
					float moved = Math.abs(event.getRawX(0) - initialTouchX) + Math.abs(event.getRawY(0) - initialTouchY);
					if (moved < dpToPx(6)) {
						gestureMode = GestureMode.SCALE;
						isScaling = true;
						suppressMoveUntilUp = true; // включаем блокировку последующих одиночных перемещений до полного отпускания
						initialDistance = getDistance(event);
						initialScale = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f);
						lastDistanceDuringScale = initialDistance;
						smoothedScale = initialScale;
						lastScaleTs = System.nanoTime();
						initialWidth = overlayParams.width;
						initialHeight = overlayParams.height;
						initialX = overlayParams.x;
						initialY = overlayParams.y;
						scalePivotX = (event.getRawX(0) + event.getRawX(1)) / 2f;
						scalePivotY = (event.getRawY(0) + event.getRawY(1)) / 2f;
						pauseAnimation();
						animationPausedForScaling = true;
						Log.d(TAG, "Scale start pivot=(" + scalePivotX + "," + scalePivotY + ") scale=" + initialScale);
					}
				}
				return true;

			case MotionEvent.ACTION_MOVE:
				if (gestureMode == GestureMode.SCALE && isScaling && pointerCount == 2) {
					float currentDistance = getDistance(event);
					if (initialDistance > 0) {
						if (lastDistanceDuringScale > 0) {
							float rawDelta = Math.abs(currentDistance - lastDistanceDuringScale) / lastDistanceDuringScale;
							if (rawDelta > 0.40f) {
								lastDistanceDuringScale = currentDistance;
								return true;
							}
						}
						float targetScale = initialScale * (currentDistance / initialDistance);
						targetScale = Math.max(0.1f, Math.min(5.0f, targetScale));
						long now = System.nanoTime();
						float dtMs = (now - lastScaleTs) / 1_000_000f;
						lastScaleTs = now;
						float alpha = Math.min(1f, dtMs / 50f);
						smoothedScale = smoothedScale + alpha * (targetScale - smoothedScale);
						if (Math.abs(targetScale - smoothedScale) < 0.005f) {
							lastDistanceDuringScale = currentDistance;
							return true;
						}
						int baseWidth = 100;
						int baseHeight = 430;
						int newWidth = Math.round(baseWidth * smoothedScale);
						int newHeight = Math.round(baseHeight * smoothedScale);
						float pivotRelativeX = scalePivotX - initialX;
						float pivotRelativeY = scalePivotY - initialY;
						float pivotRatioX = pivotRelativeX / initialWidth;
						float pivotRatioY = pivotRelativeY / initialHeight;
						int newX = Math.round(scalePivotX - (newWidth * pivotRatioX));
						int newY = Math.round(scalePivotY - (newHeight * pivotRatioY));
						applyScaleWithPosition(smoothedScale, newX, newY);
						lastDistanceDuringScale = currentDistance;
					}
				} else if (gestureMode == GestureMode.MOVE && pointerCount == 1 && !suppressMoveUntilUp) {
					float deltaX = event.getRawX() - initialTouchX;
					float deltaY = event.getRawY() - initialTouchY;
					int newX = (int) (initialX + deltaX);
					int newY = (int) (initialY + deltaY);
					if (newX != overlayParams.x || newY != overlayParams.y) {
						didMoveDuringGesture = true;
					}
					overlayParams.x = newX;
					overlayParams.y = newY;
					try { windowManager.updateViewLayout(overlayView, overlayParams); } catch (Exception e) { Log.e(TAG, "Failed to update overlay position", e); }
				}
				return true;

			case MotionEvent.ACTION_POINTER_UP:
				if (gestureMode == GestureMode.SCALE) {
					isScaling = false;
					saveOverlayScale();
					if (animationPausedForScaling) { resumeAnimation(); animationPausedForScaling = false; }
					gestureMode = GestureMode.NONE;
				}
				// Если остался один палец, ещё не снимаем блокировку (будет снята на финальный ACTION_UP)
				return true;

			case MotionEvent.ACTION_UP:
				if (gestureMode == GestureMode.SCALE) {
					isScaling = false;
					saveOverlayScale();
					if (animationPausedForScaling) { resumeAnimation(); animationPausedForScaling = false; }
				} else if (gestureMode == GestureMode.MOVE) {
					// Всегда проверяем сохранённые префы и фактическую позицию — если отличаются, сохраняем.
					android.content.SharedPreferences p = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
					int storedX = eliminateTinyOffset(p.getInt(AppState.KEY_OVERLAY_X, overlayParams.x));
					int storedY = p.getInt(AppState.KEY_OVERLAY_Y, overlayParams.y);
					boolean coordsChanged = (storedX != eliminateTinyOffset(overlayParams.x)) || (storedY != overlayParams.y);
					float totalDelta = Math.abs(event.getRawX() - initialTouchX) + Math.abs(event.getRawY() - initialTouchY);
					if (didMoveDuringGesture || coordsChanged || totalDelta >= dpToPx(2)) {
						saveOverlayPosition();
					} else {
						Log.d(TAG, "ACTION_UP MOVE: no significant movement (" + totalDelta + ") — position unchanged");
					}
				}
				gestureMode = GestureMode.NONE;
				if (pointerCount <= 1) {
					if (suppressMoveUntilUp) {
						Log.d(TAG, "All fingers up — clearing suppressMoveUntilUp");
					}
					suppressMoveUntilUp = false; // полностью освободили экран
				}
				return true;

			case MotionEvent.ACTION_CANCEL:
				isScaling = false;
				gestureMode = GestureMode.NONE;
				if (pointerCount <= 1) suppressMoveUntilUp = false;
				return true;
		}

		return false;
	}

	/**
	 * Вычисляет расстояние между двумя пальцами для pinch-to-zoom
	 */
	private float getDistance(MotionEvent event) {
		if (event.getPointerCount() < 2) {
			return 0;
		}
		float x = event.getX(0) - event.getX(1);
		float y = event.getY(0) - event.getY(1);
		return (float) Math.sqrt(x * x + y * y);
	}

    /**
     * Применяет новый масштаб к overlay окну
     */
    private void applyScale(float newScale) {
        if (overlayParams == null || windowManager == null || overlayView == null) {
            return;
        }

        // КРИТИЧЕСКИ ВАЖНО: сохраняем текущую позицию ПЕРЕД изменением размера
        int savedX = overlayParams.x;
        int savedY = overlayParams.y;

        // Базовые размеры
        int baseWidth = 100;
        int baseHeight = 430;

        // Вычисляем новые размеры
        int newWidth = Math.round(baseWidth * newScale);
        int newHeight = Math.round(baseHeight * newScale);

        overlayParams.width = newWidth;
        overlayParams.height = newHeight;
        
        // КРИТИЧЕСКИ ВАЖНО: восстанавливаем сохранённую позицию
        overlayParams.x = savedX;
        overlayParams.y = savedY;

        // Обновляем радиус скругления пропорционально масштабу
        updateCornerRadius(newScale);

        try {
            windowManager.updateViewLayout(overlayView, overlayParams);
            // НЕ трогаем alpha — она управляется только preference listener!
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply scale", e);
        }
    }    /**
     * Применяет новый масштаб и позицию к overlay окну одновременно
     */
    private void applyScaleWithPosition(float newScale, int newX, int newY) {
        if (overlayParams == null || windowManager == null) {
            return;
        }

        // Базовые размеры
        int baseWidth = 100;
        int baseHeight = 430;

        // Вычисляем новые размеры
        int newWidth = Math.round(baseWidth * newScale);
        int newHeight = Math.round(baseHeight * newScale);

        overlayParams.width = newWidth;
        overlayParams.height = newHeight;
        overlayParams.x = newX;
        overlayParams.y = newY;

        // Обновляем радиус скругления пропорционально масштабу
        updateCornerRadius(newScale);

        try {
            windowManager.updateViewLayout(overlayView, overlayParams);
            // НЕ трогаем alpha — она управляется только preference listener!
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply scale with position", e);
        }
    }	/**
	 * Обновляет радиус скругления углов overlay окна пропорционально масштабу.
	 * Базовый радиус - 6dp, увеличивается/уменьшается с масштабом.
	 */
	private void updateCornerRadius(float scale) {
		if (overlayRoot == null) {
			return;
		}

		// Базовый радиус скругления в пикселях (6dp)
		float baseRadiusDp = 6f;
		float density = getResources().getDisplayMetrics().density;
		float baseRadiusPx = baseRadiusDp * density;

		// Вычисляем новый радиус пропорционально масштабу
		float newRadiusPx = baseRadiusPx * scale;

		// Создаём новый drawable с обновлённым радиусом
		android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
		background.setColor(0xFF121212); // Тёмно-серый цвет фона
		background.setCornerRadius(newRadiusPx);

		overlayRoot.setBackground(background);
		overlayRoot.setClipToOutline(true);

		Log.d(TAG, "Corner radius updated to: " + newRadiusPx + "px (scale=" + scale + ")");
	}

	/**
	 * Сохраняет текущий масштаб в SharedPreferences
	 */
	private void saveOverlayScale() {
		if (overlayParams == null) {
			return;
		}

		// Вычисляем масштаб из текущих размеров с правильной базовой шириной
		int baseWidth = 100;
		float currentScale = (float) overlayParams.width / baseWidth;
		
		// Округляем до 2 знаков после запятой для точности
		currentScale = Math.round(currentScale * 100f) / 100f;
		
		android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		prefs.edit()
				.putFloat(AppState.KEY_OVERLAY_SCALE, currentScale)
				.apply();

		Log.d(TAG, "Overlay scale saved: " + currentScale + " (width=" + overlayParams.width + ")");
		
		// Отправляем broadcast для обновления UI в реальном времени
		android.content.Intent intent = new android.content.Intent(AppState.ACTION_OVERLAY_UPDATED);
		intent.putExtra(AppState.KEY_OVERLAY_SCALE, currentScale);
		sendBroadcast(intent);
	}

	/**
	 * Сохраняет текущую позицию overlay окна в SharedPreferences.
	 * Размеры не сохраняются, так как вычисляются из scale.
	 */
	/**
	 * Устанавливает прозрачность overlay окна
	 */
	private void setOverlayAlpha(float alpha) {
		if (overlayView == null) {
			return;
		}
		overlayView.setAlpha(alpha);
		Log.d(TAG, "Overlay alpha set to: " + alpha);
	}

	private void saveOverlayPosition() {
		if (overlayParams == null) {
			return;
		}

		android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
	int logicalX = overlayParams.x;
	// Если применяли компенсацию (x отрицательный но близок к 0), вернём в префы 0.
	if (logicalX < 0 && Math.abs(logicalX) <= computeLeftCompensation() + 2) {
		logicalX = 0;
	}
	int clampedX = eliminateTinyOffset(logicalX);

	prefs.edit()
		.putInt(AppState.KEY_OVERLAY_X, clampedX)
				.putInt(AppState.KEY_OVERLAY_Y, overlayParams.y)
				.apply();

	Log.d(TAG, "Overlay position saved: x=" + clampedX + " y=" + overlayParams.y);
	
	// Отправляем broadcast для обновления UI в реальном времени
	android.content.Intent intent = new android.content.Intent(AppState.ACTION_OVERLAY_UPDATED);
	intent.putExtra(AppState.KEY_OVERLAY_X, clampedX);
	intent.putExtra(AppState.KEY_OVERLAY_Y, overlayParams.y);
	sendBroadcast(intent);
	}

	private void refreshOverlayStatus() {
		if (overlayStateStrip == null) {
			return;
		}
		CharSequence timestamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis());
		overlayStateStrip.setContentDescription(
				getString(R.string.overlay_state_strip_cd_with_time, timestamp));
	}

	/**
	 * Снимает overlay и очищает все ссылки/обработчики, чтобы не допустить утечек WindowManager.
	 */
	private void detachOverlay() {
		if (!overlayAttached.get()) {
			overlayView = null;
			return;
		}
		cancelOverlayAnimator();
		if (windowManager != null && overlayView != null) {
			try {
				windowManager.removeViewImmediate(overlayView);
				Log.i(TAG, "Overlay detached");
			} catch (IllegalArgumentException ex) {
				Log.w(TAG, "Overlay already removed", ex);
			}
		}
		StateBus.unregisterStateListener(stripStateListener);
		overlayAttached.set(false);
		overlayView = null;
		overlayParams = null;
        overlayRoot = null;
		overlayStateStrip = null;
        overlayCrossfadeView = null;
		currentState = 0;
        lastResId = 0;
	}

	/**
	 * Создаёт малошумное foreground-уведомление. Канал создаётся отдельно, но здесь задаём иконку
	 * и PendingIntent для возврата в приложение.
	 */
	private Notification buildNotification() {
		Intent launchIntent = new Intent(this, MainActivity.class);
		launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

		PendingIntent contentIntent = PendingIntent.getActivity(
				this,
				0,
				launchIntent,
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
						? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
						: PendingIntent.FLAG_UPDATE_CURRENT
		);

		return new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle(getString(R.string.app_name))
				.setSmallIcon(R.drawable.ic_notification_light)
				.setPriority(NotificationCompat.PRIORITY_MIN)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.setSilent(true)
				.setVisibility(NotificationCompat.VISIBILITY_SECRET)
				.setContentIntent(contentIntent)
				.setOngoing(true)
				.build();
	}

	/**
	 * Формирует параметры расположения overlay: в правом нижнем углу с фиксированным отступом и без
	 * возможности взаимодействия (FLAG_NOT_TOUCHABLE). При необходимости позицию можно скорректировать.
	 */
	private WindowManager.LayoutParams buildDefaultLayoutParams() {
		int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
				: WindowManager.LayoutParams.TYPE_PHONE;

		// Загружаем сохранённые параметры
		android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		int defaultX = 0;  // РЕАЛЬНЫЙ 0!
		int defaultY = 0;  // РЕАЛЬНЫЙ 0!
		float defaultScale = 1.0f;
		
		// Базовые размеры в пикселях (соотношение 476×2047)
		int baseWidth = 100;
		int baseHeight = 430;
		
	int savedX = eliminateTinyOffset(prefs.getInt(AppState.KEY_OVERLAY_X, defaultX));
	int savedY = prefs.getInt(AppState.KEY_OVERLAY_Y, defaultY);
		float savedScale = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, defaultScale);

		// Вычисляем финальные размеры с учётом масштаба
		int finalWidth = Math.round(baseWidth * savedScale);
		int finalHeight = Math.round(baseHeight * savedScale);

		Log.i(TAG, "Loading overlay params: X=" + savedX + " Y=" + savedY + 
				" Scale=" + savedScale + " (W=" + finalWidth + " H=" + finalHeight + ")");

		// Если это первый запуск (значения не сохранены), сохраняем значения по умолчанию
		if (!prefs.contains(AppState.KEY_OVERLAY_X)) {
	    prefs.edit()
		    .putInt(AppState.KEY_OVERLAY_X, defaultX)
					.putInt(AppState.KEY_OVERLAY_Y, defaultY)
					.putFloat(AppState.KEY_OVERLAY_SCALE, defaultScale)
					.apply();
			Log.d(TAG, "Initialized default overlay position: x=" + defaultX + " y=" + defaultY +
					" scale=" + defaultScale);
		}

		WindowManager.LayoutParams params = new WindowManager.LayoutParams(
				finalWidth,
				finalHeight,
				layoutType,
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
					| WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
					| WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
				PixelFormat.TRANSLUCENT
		);
		// Используем LEFT вместо START для корректной работы координаты X
		// X: отрицательные значения = влево за границу экрана, положительные = вправо
		// Y: отрицательные значения = вверх за границу экрана, положительные = вниз
		params.gravity = Gravity.LEFT | Gravity.TOP;
		// Компенсация визуального смещения: если сохранён X == 0, вычисляем фактическую левую границу
		// через raw ширину экрана. Иногда WindowManager считает origin чуть правее (gesture inset / cutout).
		if (savedX == 0) {
			int compensation = computeLeftCompensation();
			params.x = -compensation;
			Log.d(TAG, "Applied left compensation=" + compensation + "px");
		} else {
			params.x = savedX;
		}
		params.y = savedY;
		params.setTitle("FloatingOverlayProbe");
		return params;
	}

	private int dpToPx(int dp) {
		float density = getResources().getDisplayMetrics().density;
		return Math.round(dp * density);
	}

	private int eliminateTinyOffset(int valuePx) {
		return Math.abs(valuePx) <= 12 ? 0 : valuePx;
	}

	/**
	 * Пытаемся оценить системный левый inset (жестовая зона/скошенный край). Если View при x=0
	 * визуально стоит со смещением ~10px, возвращаем среднее значение 10. В будущем можно заменить
	 * на WindowInsets (API 30+), но для простоты сейчас используем эвристику.
	 */
	private int computeLeftCompensation() {
		// Можно усложнить через WindowMetrics (API 30), но пока фикс.
		return 10; // px
	}

	private boolean canDrawOverlays() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			return true;
		}
		return Settings.canDrawOverlays(this);
	}

	/**
	 * Создаёт/пересоздаёт канал уведомлений с минимальной важностью. При повышении важности пользователь
	 * мог бы получить лишние уведомления, поэтому принудительно очищаем канал при неправильных настройках.
	 */
	private void ensureChannel() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (manager == null) {
			Log.w(TAG, "NotificationManager is null, cannot create channel");
			return;
		}
		NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
		boolean needCreate = channel == null;
		if (!needCreate && channel.getImportance() > NotificationManager.IMPORTANCE_MIN) {
			manager.deleteNotificationChannel(CHANNEL_ID);
			needCreate = true;
		}
		if (needCreate) {
			channel = new NotificationChannel(
				CHANNEL_ID,
				"Overlay probe",
				NotificationManager.IMPORTANCE_MIN
			);
			channel.enableLights(false);
			channel.enableVibration(false);
			channel.setSound(null, null);
			channel.setDescription("Минимальный сервис для диагностики убиваний системой");
			channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
			manager.createNotificationChannel(channel);
			Log.i(TAG, "Notification channel created");
		}
	}

	/**
	 * Обновляет изображение полосы. Если состояние изменилось плавно — запускаем анимацию crossfade,
	 * иначе просто подменяем drawable.
	 */
	private void updateOverlayState(int state) {
		if (overlayStateStrip == null) {
			Log.d(TAG, "updateOverlayState: view null, skip state=" + state);
			return;
		}
		int resId = resolveDrawableForState(state);
		if (resId == 0) {
			Log.w(TAG, "updateOverlayState: unresolved drawable for state=" + state);
			return;
		}
		if (state == currentState && resId == lastResId && overlayStateStrip.getDrawable() != null) {
			Log.d(TAG, "updateOverlayState: no-op (same state=" + state + ")");
			return;
		}
		Drawable newDrawable = AppCompatResources.getDrawable(this, resId);
		if (newDrawable == null) {
			Log.w(TAG, "updateOverlayState: drawable load failed resId=" + resId + " state=" + state);
			return;
		}
		if (overlayRoot == null || overlayStateStrip.getDrawable() == null || currentState == 0) {
			cancelOverlayAnimator();
			overlayStateStrip.setAlpha(1f);
			overlayStateStrip.setImageDrawable(newDrawable);
			Log.d(TAG, "updateOverlayState: applied immediately state=" + state + " resId=" + resId);
		} else {
			Log.d(TAG, "updateOverlayState: crossfade from=" + currentState + " to=" + state);
			startStripCrossfade(newDrawable);
		}
		currentState = state;
		lastResId = resId;
	}

	/**
	 * Плавно перетягивает изображение полосы с помощью дополнительного ImageView поверх основного.
	 * Анимация работает по ease-in-out кривой, чтобы сделать переход мягким.
	 */
	private void startStripCrossfade(Drawable newDrawable) {
		cancelOverlayAnimator();
		if (overlayRoot == null) {
			overlayStateStrip.setImageDrawable(newDrawable);
			overlayStateStrip.setAlpha(1f);
			return;
		}
		Drawable oldDrawable = overlayStateStrip.getDrawable();
		if (oldDrawable == null) {
			overlayStateStrip.setImageDrawable(newDrawable);
			overlayStateStrip.setAlpha(1f);
			return;
		}
		overlayStateStrip.setAlpha(1f);
		overlayStateStrip.setImageDrawable(oldDrawable);

		overlayCrossfadeView = new ImageView(this);
		int width = overlayStateStrip.getWidth();
		int height = overlayStateStrip.getHeight();
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
				width > 0 ? width : FrameLayout.LayoutParams.MATCH_PARENT,
				height > 0 ? height : FrameLayout.LayoutParams.MATCH_PARENT,
				Gravity.TOP | Gravity.START);
		overlayCrossfadeView.setLayoutParams(lp);
		overlayCrossfadeView.setScaleType(overlayStateStrip.getScaleType());
		overlayCrossfadeView.setAdjustViewBounds(overlayStateStrip.getAdjustViewBounds());
		overlayCrossfadeView.setImageDrawable(newDrawable);
		overlayCrossfadeView.setAlpha(0f);
		overlayRoot.setClipToPadding(false);
		overlayRoot.setClipChildren(false);
		overlayRoot.addView(overlayCrossfadeView);

		overlayAnimator = ValueAnimator.ofFloat(0f, 1f);
		overlayAnimator.setDuration(STRIP_ANIM_DURATION);
		overlayAnimator.addUpdateListener(anim -> {
			float t = (float) anim.getAnimatedValue();
			if (t <= 0.5f) {
				float local = t / 0.5f;
				float eased = local * local * (3f - 2f * local);
				overlayCrossfadeView.setAlpha(eased);
				overlayStateStrip.setAlpha(1f);
			} else {
				float local = (t - 0.5f) / 0.5f;
				float eased = local * local * (3f - 2f * local);
				overlayCrossfadeView.setAlpha(1f);
				overlayStateStrip.setAlpha(1f - eased);
			}
		});
		overlayAnimator.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				finishStripCrossfade(newDrawable);
			}

			@Override
			public void onAnimationCancel(Animator animation) {
				finishStripCrossfade(newDrawable);
			}
		});
		overlayAnimator.start();
	}

	/**
	 * Завершает анимацию: переносит итоговый drawable на основной ImageView и убирает временное.
	 */
	private void finishStripCrossfade(Drawable finalDrawable) {
		overlayStateStrip.setImageDrawable(finalDrawable);
		overlayStateStrip.setAlpha(1f);
		if (overlayRoot != null && overlayCrossfadeView != null) {
			overlayRoot.removeView(overlayCrossfadeView);
		}
		overlayCrossfadeView = null;
		overlayAnimator = null;
	}

	private void cancelOverlayAnimator() {
		if (overlayAnimator != null) {
			overlayAnimator.cancel();
		} else if (overlayRoot != null && overlayCrossfadeView != null) {
			overlayRoot.removeView(overlayCrossfadeView);
			overlayCrossfadeView = null;
		}
	}

	/**
	 * Приостанавливает анимацию overlay окна
	 */
	private void pauseAnimation() {
		if (overlayAnimator != null && overlayAnimator.isRunning()) {
			overlayAnimator.pause();
			Log.d(TAG, "Animation paused");
		}
	}

	/**
	 * Возобновляет анимацию overlay окна
	 */
	private void resumeAnimation() {
		if (overlayAnimator != null && overlayAnimator.isPaused()) {
			overlayAnimator.resume();
			Log.d(TAG, "Animation resumed");
		}
	}

	private int resolveDrawableForState(int state) {
		switch (state) {
			case 1:
				return R.drawable.state_01_green;
			case 2:
				return R.drawable.state_02_yellow;
			case 3:
				return R.drawable.state_03_red_yellow;
			case 4:
				return R.drawable.state_04_red;
			case 5:
				return R.drawable.state_05_white;
			default:
				return 0;
		}
	}
}
