package com.example.androidbuttons;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.LinkedBlockingQueue;

public final class AppState {
    /**
     * Утилитарный контейнер с глобальными флагами/очередями. Чтобы исключить создание экземпляра,
     * объявляем приватный конструктор без тела.
     */
    private AppState() {}

    /**
     * Номер текущего выбранного локомотива (1..8). Используем AtomicInteger, чтобы изменения из
     * разных потоков (UI/сервисов) были атомарными и не требовали дополнительной синхронизации.
     */
    public static final AtomicInteger selectedLoco = new AtomicInteger(1);

    /**
     * Потокобезопасная очередь для накопления логов между сервисами и экраном настроек. Очередь
     * наполняется в MainActivity и сервисах, а SettingsActivity периодически считывает её содержимое.
     */
    public static final LinkedBlockingQueue<String> consoleQueue = new LinkedBlockingQueue<>();

    /**
     * Название файла SharedPreferences и ключи настроек сети. Используются как в
     * SettingsActivity для записи, так и в MainActivity при первичной инициализации компонентов.
     */
    public static final String PREFS_NAME = "androidbuttons_prefs";
    public static final String KEY_TCP_HOST = "tcp_host";
    public static final String KEY_TCP_PORT = "tcp_port";
    
    /**
     * Ключи для сохранения позиции и масштаба overlay окна.
     */
    public static final String KEY_OVERLAY_X = "overlay_x";
    public static final String KEY_OVERLAY_Y = "overlay_y";
    public static final String KEY_OVERLAY_SCALE = "overlay_scale";
    public static final String KEY_OVERLAY_ALLOW_MODIFICATION = "overlay_allow_modification";
    
    /**
     * Broadcast action для уведомления об изменении позиции или масштаба overlay окна.
     */
    public static final String ACTION_OVERLAY_UPDATED = "com.example.androidbuttons.OVERLAY_UPDATED";

    /**
     * Флаги состояния подключения. Они обновляются менеджером TCP и отображаются в интерфейсе
     * настроек. Используем volatile, чтобы изменения были видимы между потоками без блокировок.
     */
    public static volatile boolean tcpConnecting = false;
    public static volatile boolean tcpConnected = false;
    public static volatile boolean tcpReachable = false;
}
