package org.example.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.Scanner;

/**
 * 电梯代理服务器 - 中转电梯和多个机器人之间的通信
 * 
 * 架构：
 * 电梯 <---> 代理服务器 <---> 机器人1
 *                      <---> 机器人2
 *                      <---> 机器人N
 */
public class ElevatorProxyServer {
    private final String elevatorHost;
    private final int elevatorPort;
    private final int proxyPort;
    
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    
    private ElevatorManager elevatorManager;
    private RobotManager robotManager;
    private BusinessLogicHandler businessHandler;
    
    public ElevatorProxyServer(String elevatorHost, int elevatorPort, int proxyPort) {
        this.elevatorHost = elevatorHost;
        this.elevatorPort = elevatorPort;
        this.proxyPort = proxyPort;
    }
    
    /**
     * 启动代理服务器
     */
    public void start() {
        System.out.println("========================================");
        System.out.println("电梯代理服务器启动");
        System.out.println("电梯地址: " + elevatorHost + ":" + elevatorPort);
        System.out.println("代理端口: " + proxyPort);
        System.out.println("========================================");
        
        // 初始化业务逻辑处理器
        businessHandler = new BusinessLogicHandler();
        
        // 初始化电梯管理器
        elevatorManager = new ElevatorManager(elevatorHost, elevatorPort);
        elevatorManager.setMessageHandler(this::onElevatorMessage);
        elevatorManager.start();
        
        // 初始化机器人管理器
        robotManager = new RobotManager();
        robotManager.setMessageHandler(new RobotManager.RobotMessageHandler() {
            @Override
            public void onMessage(String robotId, ProxyMessage msg) {
                onRobotMessage(robotId, msg);
            }
            
            @Override
            public void onConnect(String robotId) {
                System.out.println("[代理] " + robotId + " 已连接，当前在线: " + robotManager.getOnlineCount());
            }
            
            @Override
            public void onDisconnect(String robotId) {
                System.out.println("[代理] " + robotId + " 已断开，当前在线: " + robotManager.getOnlineCount());
            }
        });
        
        // 启动代理服务器（监听机器人连接）
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(robotManager.createPipeline());
        
        try {
            bootstrap.bind(proxyPort).sync();
            System.out.println("[代理] 服务器已启动，监听端口: " + proxyPort);
        } catch (InterruptedException e) {
            System.err.println("[代理] 启动失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理电梯消息
     */
    private void onElevatorMessage(ProxyMessage msg) {
        System.out.println("[代理] 收到电梯消息: " + msg);
        
        // 业务逻辑处理
        if (!businessHandler.handleElevatorMessage(msg)) {
            System.out.println("[代理] 电梯消息被拦截");
            return;
        }
        
        // 广播给所有机器人
        robotManager.broadcastToAll(msg);
    }
    
    /**
     * 处理机器人消息
     */
    private void onRobotMessage(String robotId, ProxyMessage msg) {
        System.out.println("[代理] 收到 " + robotId + " 消息: " + msg);
        
        // 业务逻辑处理（消息过滤）
        if (!businessHandler.handleRobotMessage(robotId, msg)) {
            System.out.println("[代理] " + robotId + " 消息被拦截");
            return;
        }
        
        // 转发给电梯
        if (elevatorManager.isConnected()) {
            elevatorManager.sendMessage(msg);
        } else {
            System.out.println("[代理] 电梯未连接，无法转发");
        }
    }
    
    /**
     * 停止代理服务器
     */
    public void stop() {
        System.out.println("[代理] 正在停止...");
        
        if (elevatorManager != null) {
            elevatorManager.stop();
        }
        
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        
        System.out.println("[代理] 已停止");
    }
    
    /**
     * 获取业务逻辑处理器
     */
    public BusinessLogicHandler getBusinessHandler() {
        return businessHandler;
    }
    
    /**
     * 获取机器人管理器
     */
    public RobotManager getRobotManager() {
        return robotManager;
    }
    
    /**
     * 获取电梯管理器
     */
    public ElevatorManager getElevatorManager() {
        return elevatorManager;
    }
    
}
