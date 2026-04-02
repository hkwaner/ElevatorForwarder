package org.example.proxy;

/**
 * 消息类型定义
 */
public class MessageType {
    
    // ========== 电梯广播消息类型 ==========
    public static final byte ELEVATOR_STATUS = 0x01;      // 电梯状态广播
    public static final byte ELEVATOR_FLOOR = 0x02;       // 楼层信息
    public static final byte ELEVATOR_DOOR = 0x03;        // 门状态
    
    // ========== 机器人请求消息类型 ==========
    public static final byte ROBOT_SELECT_FLOOR = 0x10;   // 选楼层
    public static final byte ROBOT_OPEN_DOOR = 0x11;      // 开门请求
    public static final byte ROBOT_CLOSE_DOOR = 0x12;     // 关门请求
    public static final byte ROBOT_HEARTBEAT = 0x13;      // 心跳
    
    /**
     * 获取消息类型描述
     */
    public static String getDescription(byte type) {
        switch (type) {
            case ELEVATOR_STATUS: return "电梯状态广播";
            case ELEVATOR_FLOOR: return "楼层信息";
            case ELEVATOR_DOOR: return "门状态";
            case ROBOT_SELECT_FLOOR: return "选楼层";
            case ROBOT_OPEN_DOOR: return "开门请求";
            case ROBOT_CLOSE_DOOR: return "关门请求";
            case ROBOT_HEARTBEAT: return "心跳";
            default: return "未知类型: 0x" + String.format("%02X", type);
        }
    }
}
