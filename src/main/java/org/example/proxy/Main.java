package org.example.proxy;

import java.util.Scanner;

public class Main {
/**
     * 主函数
     */
    public static void main(String[] args) {
        String elevatorHost = "127.0.0.1";
        int elevatorPort = 502;
        int proxyPort = 6000;  // 机器人连接此端口
        
        ElevatorProxyServer server = new ElevatorProxyServer(elevatorHost, elevatorPort, proxyPort);
        server.start();
        
        // 交互式命令
        Scanner scanner = new Scanner(System.in);
        System.out.println("\n输入命令：");
        System.out.println("  status - 查看状态");
        System.out.println("  robots - 查看在线机器人");
        System.out.println("  exit|close|quit|stop - 退出");
        
        while (true) {
            String input = scanner.nextLine().trim();
            
            switch (input) {
                case "status":
                    System.out.println("电梯连接: " + (server.getElevatorManager().isConnected() ? "已连接" : "未连接"));
                    System.out.println("在线机器人: " + server.getRobotManager().getOnlineCount());
                    break;
                    
                case "robots":
                    System.out.println("在线机器人: " + server.getRobotManager().getOnlineRobots());
                    break;
                    
                case "exit":
                case "close":
                case "quit":
                case "stop":
                    server.stop();
                    scanner.close();
                    System.exit(0);
                    break;
                    
                default:
                    System.out.println("未知命令: " + input);
            }
        }
    }
}
