package org.example.proxy;

/**
 * 代理消息 - 统一的消息封装
 */
public class ProxyMessage {
    private byte deviceId;      // 设备标识
    private byte msgType;       // 消息类型
    private byte data1;         // 数据1
    private byte data2;         // 数据2
    private byte data3;         // 数据3
    private byte crc;           // CRC校验
    private String source;      // 来源：elevator 或 robot-{id}
    
    public ProxyMessage() {}
    
    public ProxyMessage(byte deviceId, byte msgType, byte data1, byte data2, byte data3) {
        this.deviceId = deviceId;
        this.msgType = msgType;
        this.data1 = data1;
        this.data2 = data2;
        this.data3 = data3;
    }
    
    /**
     * 从字节数组解析消息（电梯广播：6字节）
     */
    public static ProxyMessage fromElevatorBytes(byte[] data) {
        if (data.length != 6) {
            return null;
        }
        
        ProxyMessage msg = new ProxyMessage();
        msg.deviceId = data[0];
        msg.msgType = data[1];
        msg.data1 = data[2];
        msg.data2 = data[3];
        msg.data3 = data[4];
        msg.crc = data[5];
        msg.source = "elevator";
        
        return msg;
    }
    
    /**
     * 从字节数组解析消息（机器人发送：5字节）
     */
    public static ProxyMessage fromRobotBytes(byte[] data) {
        if (data.length != 5) {
            return null;
        }
        
        ProxyMessage msg = new ProxyMessage();
        msg.deviceId = data[0];
        msg.msgType = data[1];
        msg.data1 = data[2];
        msg.data2 = data[3];
        msg.crc = data[4];
        msg.source = "robot";
        
        return msg;
    }
    
    /**
     * 转换为字节数组（发送给电梯：5字节）
     */
    public byte[] toElevatorBytes() {
        byte[] data = new byte[]{deviceId, msgType, data1, data2, data3};
        byte crc = Utils.getCRC(data);
        return new byte[]{deviceId, msgType, data1, data2, crc};
    }

    /**
     * 转换为字节数组（发送给机器人：6字节）
     */
    public byte[] toRobotBytes() {
        byte[] data = new byte[]{deviceId, msgType, data1, data2, data3};
        byte crc = Utils.getCRC(data);
        return new byte[]{deviceId, msgType, data1, data2, data3, crc};
    }

    /**
     * 验证CRC（电梯消息）
     */
    public boolean validateCrc() {
        byte[] data = new byte[]{deviceId, msgType, data1, data2, data3};
        byte calcCrc = Utils.getCRC(data);
        return crc == calcCrc;
    }
    
    @Override
    public String toString() {
        return String.format("ProxyMessage{source=%s, device=0x%02X, type=%s(0x%02X), data=[0x%02X, 0x%02X, 0x%02X]}",
                source, deviceId, MessageType.getDescription(msgType), msgType, data1, data2, data3);
    }
    
    // Getters and Setters
    public byte getDeviceId() { return deviceId; }
    public void setDeviceId(byte deviceId) { this.deviceId = deviceId; }
    
    public byte getMsgType() { return msgType; }
    public void setMsgType(byte msgType) { this.msgType = msgType; }
    
    public byte getData1() { return data1; }
    public void setData1(byte data1) { this.data1 = data1; }
    
    public byte getData2() { return data2; }
    public void setData2(byte data2) { this.data2 = data2; }
    
    public byte getData3() { return data3; }
    public void setData3(byte data3) { this.data3 = data3; }
    
    public byte getCrc() { return crc; }
    public void setCrc(byte crc) { this.crc = crc; }
    
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }


}
