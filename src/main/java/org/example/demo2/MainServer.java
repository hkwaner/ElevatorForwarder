package org.example.demo2;

import org.example.demo2.elevator.ElevatorConnector;
import org.example.demo2.mqtt.MqttManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 电梯 MQTT 代理服务器 - 中转电梯和多个机器人之间的通信
 */
public class MainServer {
    private static final Logger log = LoggerFactory.getLogger(MainServer.class);

    /**
     * 启动代理服务器
     */
    public void start() {
        log.info("========================================>>>");
        log.info("[代理] 正在启动,版本:{}", Config.APP_VERSION);
        // 先恢复上次的工作模式(就地/远程),须在连接电梯之前,
        // 就地模式下避免程序启动即抢占现场手控面板
        LogicHandler.getInstance().initWorkModeFromDisk();

        // 初始化电梯连接
        ElevatorConnector.getInstance().start();

        // 初始化mqtt连接
        MqttManager.getInstance().start();

        // 启动看门狗，检测 handler 卡死并自动恢复
        new Watchdog().start();
        log.info("========================================<<<");
    }

    /**
     * 停止代理服务器
     */
    public void stop() {
        log.info("[代理] 正在停止...");
        MqttManager.getInstance().stop();
        ElevatorConnector.getInstance().stop();
        log.info("[代理] 已停止");
    }
}
