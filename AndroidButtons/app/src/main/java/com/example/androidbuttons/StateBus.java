package com.example.androidbuttons;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Лёгкая «шина» для обмена состоянием светодиодной полосы между активити и overlay-сервисом.
 * Слушатели регистрируются в CopyOnWriteArrayList, что избавляет от ручной синхронизации и
 * корректно работает при редком количестве подписчиков.
 */
public final class StateBus {

	private StateBus() {}

	public interface StripStateListener {
		/**
		 * Вызывается, когда появляется новое «абсолютное» состояние полосы. Сервису достаточно
		 * обновить отображение, не отправляя событий назад.
		 */
		void onStripStateChanged(int state);
	}

	public interface OverlaySelectionListener {
		/**
		 * Срабатывает, когда пользователь выбирает состояние через overlay. MainActivity должна
		 * отреагировать и отправить команду управлению.
		 */
		void onOverlayStateSelected(int state);
	}

	private static final CopyOnWriteArrayList<StripStateListener> stateListeners = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<OverlaySelectionListener> selectionListeners = new CopyOnWriteArrayList<>();
	private static final AtomicInteger currentState = new AtomicInteger(0);

	/**
	 * Возвращает текущее зафиксированное состояние (0 если не установлено).
	 */
	public static int getCurrentState() {
		return currentState.get();
	}

	public static void registerStateListener(StripStateListener listener) {
		if (listener == null) return;
		stateListeners.addIfAbsent(listener);
		int state = currentState.get();
		if (state > 0) {
			// При подключении нового слушателя сразу отправляем актуальное состояние, чтобы он не
			// ожидал следующего события.
			listener.onStripStateChanged(state);
		}
	}

	public static void unregisterStateListener(StripStateListener listener) {
		if (listener == null) return;
		stateListeners.remove(listener);
	}

	public static void registerSelectionListener(OverlaySelectionListener listener) {
		if (listener == null) return;
		selectionListeners.addIfAbsent(listener);
	}

	public static void unregisterSelectionListener(OverlaySelectionListener listener) {
		if (listener == null) return;
		selectionListeners.remove(listener);
	}

	public static void publishStripState(int state) {
		if (state <= 0) {
			currentState.set(0);
			return;
		}
		currentState.set(state);
		// CopyOnWriteArrayList безопасна к итерированию без синхронизации — храним компактный
		// список, поэтому накладные расходы копирования минимальны.
		for (StripStateListener listener : stateListeners) {
			listener.onStripStateChanged(state);
		}
	}

	public static void publishOverlaySelection(int state) {
		if (state <= 0) return;
		for (OverlaySelectionListener listener : selectionListeners) {
			listener.onOverlayStateSelected(state);
		}
	}
}
