package org.example.proxy;

/**
 * 业务逻辑处理器 - 消息过滤
 */
public class BusinessLogicHandler {
    
    /**
     * 处理机器人发来的消息
     * @return true 表示允许转发，false 表示拦截
     */
    public boolean handleRobotMessage(String robotId, ProxyMessage msg) {
        // 1. 消息内容验证
        if (!validateMessageContent(msg)) {
            System.out.println("[业务] 消息内容验证失败: " + msg);
            return false;
        }
        
        // 2. 业务规则检查
        if (!checkBusinessRules(robotId, msg)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 处理电梯发来的消息
     * @return true 表示允许转发，false 表示拦截
     */
    public boolean handleElevatorMessage(ProxyMessage msg) {
        // 电梯消息默认全部转发给机器人
        // 可以在这里添加过滤逻辑
        
        // 例如：只转发状态广播，过滤其他消息
        // if (msg.getMsgType() != MessageType.ELEVATOR_STATUS) {
        //     return false;
        // }
        
        return true;
    }
    
    /**
     * 验证消息内容
     */
    private boolean validateMessageContent(ProxyMessage msg) {
        // 检查楼层范围（假设 data1 表示楼层）
        if (msg.getMsgType() == MessageType.ROBOT_SELECT_FLOOR) {
            int floor = msg.getData1() & 0xFF;
            if (floor < 1 || floor > 50) {  // 假设楼层范围 1-50
                System.out.println("[业务] 楼层超出范围: " + floor);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 业务规则检查
     */
    private boolean checkBusinessRules(String robotId, ProxyMessage msg) {
        // 示例规则：
        // 1. 同一时间只允许一个机器人控制电梯
        // 2. 开门请求必须在电梯停止且平层时才能发送
        
        // 这里可以实现更复杂的业务逻辑
        // 例如：检查电梯当前状态、机器人排队等
        
        return true;
    }
}
