package org.example.demo2.elevator;

import org.example.demo2.mqtt.MqttManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 处理接收到的电梯消息 处理后通过mqtt广播出去
 */
public class ElevatorResultHandler extends Thread {
    private static final Logger log = LoggerFactory.getLogger(ElevatorResultHandler.class);
    private volatile boolean runFlag = true;
    private MqttManager mqttManager = MqttManager.getInstance();

    private ElevatorResultHandler() {
        setName("ElevatorResultHandler");
    }

    private static final class InstanceHolder {
        private static final ElevatorResultHandler INSTANCE = new ElevatorResultHandler();
    }

    public static ElevatorResultHandler getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private ElevatorResult lastResult;

    private boolean checkResultDataDiff(ElevatorResult result1, ElevatorResult result2) {
        //todo 多个电梯的场景下 根据设备地址区分判断????
        if (result1 == null || result2 == null) return false;

        byte[] originalData1 = result1.getOriginalData();
        byte[] originalData2 = result2.getOriginalData();
        if (originalData1 == null || originalData2 == null || originalData1.length == 0 || originalData2.length == 0)
            return false;
        return !Arrays.equals(originalData1, originalData2);
    }

    @Override
    public void run() {
        while (runFlag) {
            ElevatorResult elevatorResult = ElevatorConnector.getInstance().getLastElevatorResult();
            if (elevatorResult != null) {
                if (lastResult == null) {//第一次只要消息部位null就直接广播出去
                    lastResult = elevatorResult;
                    mqttManager.broadcastElevatorResult(lastResult);//将电梯的状态广播出去
                } else {
                    //判断下时间 如果间隔太短就先不广播 消息间隔到达一定时间再广播 防止一下接收到电梯很多消息全广播出去
                    long receiveTimeNano0 = elevatorResult.getReceiveTimeNano();
                    long receiveTimeNano1 = lastResult.getReceiveTimeNano();//最近一次广播的电梯消息的时间
                    //时间条件 最近接收到的电梯消息的时间必须大于上次广播的消息的时间
                    boolean timeFlag = receiveTimeNano0 - receiveTimeNano1 > 0;
                    //差异条件
                    boolean diffFlag = checkResultDataDiff(lastResult, elevatorResult);
                    //当时间条件达成的情况下 如果两条消息有差异就直接广播出去 如果没有差异如果两条消息内data内容相等的情况下 两次消息间隔大于500毫秒 才广播
                    boolean sendFlag = timeFlag &&
                            (diffFlag || receiveTimeNano0 - receiveTimeNano1 > TimeUnit.MILLISECONDS.toNanos(500));
                    if (sendFlag) {
                        lastResult = elevatorResult;
                        mqttManager.broadcastElevatorResult(lastResult);//将电梯的状态广播出去
                    }
                }
            }

            try {
                sleep(100);
            } catch (InterruptedException e) {
                log.info("电梯消息处理 sleep error", e);
            }
        }
    }

    public void stopRun() {
        runFlag = false;
    }
}
