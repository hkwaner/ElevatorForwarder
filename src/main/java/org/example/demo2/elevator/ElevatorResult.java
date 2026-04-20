package org.example.demo2.elevator;

import org.example.demo2.LogicHandler;
import org.example.demo2.MainServer;
import org.example.demo2.utils.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class ElevatorResult implements Serializable {
    private static final Logger log = LoggerFactory.getLogger(ElevatorResult.class);

    private static SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    private byte[] originalData;        // [0]设备标识 [1]data0 轿厢楼层信息 [2]data1 电梯状态 [3]data2 机器人独占地址 [4]data3 保留 [5]CRC校验

    private transient long receiveTimeNano;       //接收到消息时的时间戳(纳秒时间戳)
    private long receiveTime;           //接收到消息的时间戳(毫秒)
    //接续获取到的信息

    //data0
    private boolean isLeveling;         // 轿厢是否在平层
    private int floor;                  // 轿厢停靠楼层
    private boolean isMovingUp;         // 电梯上行
    private boolean isMovingDown;       // 电梯下行
    private boolean isMoving;           // 电梯运动中

    //data1
    private String status;   // 电梯状态是否正常

    //data2
    private transient boolean isOccupiedError;    // 独占异常

    private String occupiedUser;        // 当前独占的用户id
    private String occupiedUserName;    // 当前独占的用户名称

    private ElevatorResult() {
    }

    /**
     * 处理从电梯服务接收到的消息
     */
    public static ElevatorResult convertMsg(byte[] data) {
        if (!checkCrc(data)) {
            log.info("ElevatorMsg 校验消息crc失败 data:{}", HexUtils.bytesToHexString(data));
            return null;
        }

        ElevatorResult msg = new ElevatorResult();
        msg.receiveTimeNano = System.nanoTime();
        msg.receiveTime = System.currentTimeMillis();
        msg.originalData = data;

        //解析data0
        msg.isLeveling = (msg.originalData[1] & STATUS_FLOOR_MASK) != 0;          // 解析是否在平层
        int floorValue = msg.originalData[1] & STATUS_FLOOR_MASK;                 // 解析楼层
        msg.floor = floorValue > 0 ? floorValue : 0;                              // 0表示不在平层
        msg.isMovingUp = (msg.originalData[1] & STATUS_MOVING_UP) != 0;           // 解析上行状态
        msg.isMovingDown = (msg.originalData[1] & STATUS_MOVING_DOWN) != 0;       // 解析下行状态
        msg.isMoving = (msg.originalData[1] & STATUS_IN_MOTION) != 0;             // 解析运动状态

        //解析data1
        msg.status = msg.originalData[2] == STATUS_ELEVATOR_NORMAL ? "正常" : HexUtils.byteToHexString(msg.originalData[2]);    // 解析电梯状态是否正常

        //解析data2
        msg.isOccupiedError = (msg.originalData[3] & STATUS_OCCUPIED_ERROR) != 0;  // 解析是否独占异常
        if (msg.isOccupiedError) msg.status = "独占异常";

        String[] currentOccupyElevatorUser = LogicHandler.getInstance().getCurrentOccupyElevatorUser();
        if (currentOccupyElevatorUser != null) {
            msg.occupiedUser = currentOccupyElevatorUser[0];
            msg.occupiedUserName = currentOccupyElevatorUser[1];
        }
        //data3 不用

        return msg;
    }


    public int getFloor() {
        if (isLeveling && floor > 0) return floor;
        return 0;   // 0表示不在平层
    }

    public boolean isMoving() {
        return isMoving || isMovingUp || isMovingDown;//不知道会不会出现 比如 电梯不在运动中 但是 有电梯上行 所以保险点当三个都为false才表示电梯不在运动中
    }

    public String getStatus() {
        return status;
    }

    public boolean isOccupiedError() {
        return isOccupiedError;
    }

    public String getOccupiedUser() {
        return occupiedUser;
    }

    protected long getReceiveTimeNano() {
        return receiveTimeNano;
    }

    public long getReceiveTime() {
        return receiveTime;
    }

    public byte[] getOriginalData() {
        return originalData;
    }

    @Override
    public String toString() {
        return "原始数据:" + HexUtils.bytesToHexString(originalData) +
                ",在平层:" + isLeveling +
                ",楼层:" + floor +
                ",上行中:" + isMovingUp +
                ",下行中:" + isMovingDown +
                ",运动中:" + isMoving +
                ",状态:" + status +
                ",当前独占用户:" + occupiedUser +
                ",时间:" + simpleDateFormat.format(receiveTime);
    }

    /**
     * 验证CRC（电梯消息）
     */
    private static boolean checkCrc(byte[] data) {
        if (data.length != 6) return false;
        byte calcCrc = HexUtils.getCRC(new byte[]{data[0], data[1], data[2], data[3], data[4]});
        return data[5] == calcCrc;
    }


    /// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // 状态位定义
    public static final byte NOT_AT_FLOOR = (byte) 0x00;            // 0000 0000 - 不在平层
    public static final byte STATUS_FLOOR_MASK = (byte) 0x1F;       // 0001 1111 - 楼层 (0x01-0x1F对应1-31楼)
    public static final byte STATUS_MOVING_UP = (byte) 0x20;        // 0010 0000 - 上行标志
    public static final byte STATUS_MOVING_DOWN = (byte) 0x40;      // 0100 0000 - 下行标志
    public static final byte STATUS_IN_MOTION = (byte) 0x80;        // 1000 0000 - 运行中标志

    /**
     * 电梯状态 <br>
     * 0x00 正常状态 <br>
     * 0x01 检修状态 <br>
     * 0x02 相序故障 <br>
     * 0x03 接地故障 <br>
     * 0x04 运行次数 <br>
     * 0x05 急停故障 <br>
     * 0x06 门锁故障 <br>
     * 0x07 上限位故障 <br>
     * 0x08 下限位故障 <br>
     * 0x09 接触器粘连故障 <br>
     * 0x0a 继电器粘连故障 <br>
     * 0x0b 出站超时故障 <br>
     * 0x0c 运行超时故障 <br>
     * 0x0d 多站输入故障 <br>
     * 0x0e 锁梯状态 <br>
     * 0x0f 变频器故障 <br>
     * 0xff
     */
    public static final byte STATUS_ELEVATOR_NORMAL = 0x00;

    //独占异常
    public static final byte STATUS_OCCUPIED_ERROR = (byte) 0xFF;


}
