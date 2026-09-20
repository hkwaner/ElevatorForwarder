package org.example.demo2;

import org.example.demo2.elevator.ElevatorResultHandler;
import org.example.demo2.mqtt.MqttManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 看门狗 - 检测 ElevatorResultHandler 卡死，自动重启 MQTT 客户端和 handler 线程
 */
public class Watchdog {
    private static final Logger log = LoggerFactory.getLogger(Watchdog.class);

    private static final long CHECK_INTERVAL_MS = 10_000;
    private static final long STALL_THRESHOLD_MS = 30_000;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Watchdog");
        t.setDaemon(true);
        return t;
    });

    public void start() {
        scheduler.scheduleWithFixedDelay(this::check, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("[看门狗] 已启动，检查间隔 {}s，卡死阈值 {}s", CHECK_INTERVAL_MS / 1000, STALL_THRESHOLD_MS / 1000);
    }

    private void check() {
        try {
            ElevatorResultHandler handler = ElevatorResultHandler.getInstance();
            long stallMs = System.currentTimeMillis() - handler.getLastLoopTimeMs();
            if (stallMs > STALL_THRESHOLD_MS) {
                log.error("[看门狗] ElevatorResultHandler 疑似卡死 {}ms，执行恢复", stallMs);
                // 先强制重启 MQTT 客户端，关闭卡住的 socket 以解除 publish 阻塞，再重建 handler 线程
                MqttManager.getInstance().restartClient();
                handler.restart();
            }
        } catch (Exception e) {
            log.error("[看门狗] 检查异常", e);
        }
    }
}
