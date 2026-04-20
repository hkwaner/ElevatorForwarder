package org.example.demo2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * MQTT 版本电梯代理服务器启动类
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        MainServer server = new MainServer();
        log.info("服务启动>>> tag1");
        server.start();
        log.info("服务启动<<<");
    }
}
