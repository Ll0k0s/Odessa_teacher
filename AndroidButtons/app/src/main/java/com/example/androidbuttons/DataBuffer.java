package com.example.androidbuttons;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Буферизатор коротких строк, отправляемых в консоль. Позволяет сгруппировать сообщения и
 * уменьшить количество обращений к UI-потоку.
 */
class DataBuffer implements AutoCloseable {
    /**
     * Минимальный функциональный интерфейс для «потребителя» буфера. Реализация передаётся при
     * создании (см. MainActivity), и вызывается на рабочем потоке таймера.
     */
    interface StringConsumer { void accept(String s); }

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final int maxFlushBytes;
    private final StringConsumer consumer;
    private final Timer timer = new Timer("ui-buf", true);

    DataBuffer(int maxFlushBytes, StringConsumer consumer) {
        // Гарантируем минимальную ёмкость, чтобы не выстраивать слишком мелкие пакеты.
        this.maxFlushBytes = Math.max(64, maxFlushBytes);
        this.consumer = consumer;
        // Периодически пытаемся слить накопленные сообщения. Таймер Daemon, чтобы не мешать
        // завершению приложения.
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() { flush(); }
        }, 100, 100);
    }

    void offer(String s) {
        // Не помещаем пустые строки, чтобы не загромождать вывод.
        if (s == null || s.isEmpty()) return;
        queue.offer(s);
    }

    private void flush() {
        // Если сообщений нет — лишний раз не дергаем потребителя.
        if (queue.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        // Считываем пока не достигли лимита байт либо очередь не опустела.
        while (!queue.isEmpty() && sb.length() < maxFlushBytes) {
            String s = queue.poll();
            if (s == null) break;
            sb.append(s);
        }
        // Передаём накопленный блок в колбэк. Потребитель позаботится о доставке на UI-поток.
        if (sb.length() > 0 && consumer != null) consumer.accept(sb.toString());
    }

    @Override
    public void close() {
        // Отменяем таймер и перед закрытием отдаём все накопленные данные.
        timer.cancel();
        flush();
    }
}
