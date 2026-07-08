package org.example.demo2.mqtt;

import org.example.demo2.LogicHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制选层的时候判断下机器人是否在安全的点。电梯内或候梯点 进出电梯或者去候梯点的时候 不要控制电梯上下楼
 */
public class MqttMsgHandler implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MqttMsgHandler.class);
    private static final MqttManager mqttManager = MqttManager.getInstance();
    private static final LogicHandler logicHandler = LogicHandler.getInstance();


    private MqttMsg mqttMsg;

    public MqttMsgHandler(String topic, MqttMsg mqttMsg) {
        this.mqttMsg = mqttMsg;
    }

    @Override
    public void run() {
        if (mqttMsg == null) log.info("mqttMsg");

        String source = mqttMsg.getSource();
        if (source == null || source.isEmpty()) return;

        if (mqttMsg.getType().equals(MqttConstants.TYPE_ELEVATOR_CONTROL)) {
            switch (mqttMsg.getAction()) {
                case MqttConstants.ACTION_OCCUPY_ELEVATOR://独占电梯
                    log.info("独占电梯 >>>");
                    logicHandler.occupyElevator(mqttMsg);
                    log.info("独占电梯 <<<");
                    break;
                case MqttConstants.ACTION_ENTER_ELEVATOR://通知机器人进入电梯
                    log.info("通知机器人进入电梯 >>>");
                    logicHandler.notifyRobotEnterElevator(mqttMsg);
                    log.info("通知机器人进入电梯 <<<");
                    break;
                case MqttConstants.ACTION_SELECT_FLOORS://选层
                    log.info("选层 >>>");
                    logicHandler.selectFloor(mqttMsg);
                    log.info("选层 <<<");
                    break;
                case MqttConstants.ACTION_EXIT_ELEVATOR://通知机器人出去电梯
                    log.info("通知机器人出去电梯 >>>");
                    logicHandler.notifyRobotExitElevator(mqttMsg);
                    log.info("通知机器人出去电梯 <<<");
                    break;
                case MqttConstants.ACTION_TO_WAITING_POINT://通知机器人去候梯点
                    log.info("通知机器人去候梯点 >>>");
                    logicHandler.notifyRobotToWaitingPoint(mqttMsg);
                    log.info("通知机器人去候梯点 <<<");
                    break;
                case MqttConstants.ACTION_RELEASE_ELEVATOR://取消独占
                    log.info("取消独占 >>>");
                    logicHandler.releaseElevator(mqttMsg);
                    log.info("取消独占 <<<");
                    break;
                default:
                    break;
            }
        }
    }
}
