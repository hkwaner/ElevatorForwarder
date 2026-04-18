package org.example.demo2.elevator;

import org.example.demo2.utils.HexUtils;

import java.io.Serializable;

/**
 * 控制电梯的消息
 */
public class ElevatorCommand implements Serializable {
    private byte elevatorAddress = (byte) 0xA0;   //目标电梯设备地址    /todo 暂时是固定的 多电梯场景兼容
    private byte data0;             // 选层
    private byte data1;             // 独占
    private byte data2;             // 控制门指令暂时没用
    private byte crc;               // CRC校验

    public ElevatorCommand() {
    }

    /**
     * 生成要发送给电梯服务的消息
     */
    public static ElevatorCommand buildToElevatorMsg(byte data0, byte data1, byte data2) {
        ElevatorCommand command = new ElevatorCommand();
        command.data0 = data0;
        command.data1 = data1;
        command.data2 = data2;

        command.crc = HexUtils.getCRC(new byte[]{command.elevatorAddress, command.data0, command.data1, command.data2});
        return command;
    }

    public byte[] getBytes() {
        return new byte[]{elevatorAddress, data0, data1, data2, crc};
    }


    @Override
    public String toString() {
        return HexUtils.byteToHexString(elevatorAddress) + " " +
                HexUtils.byteToHexString(data0) + " " +
                HexUtils.byteToHexString(data1) + " " +
                HexUtils.byteToHexString(data2) + " " +
                HexUtils.byteToHexString(crc);
    }
}
