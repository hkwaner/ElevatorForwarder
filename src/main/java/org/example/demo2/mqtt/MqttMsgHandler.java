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
                    String result0 = logicHandler.occupyElevator(mqttMsg);
                    mqttManager.sendResult(mqttMsg, result0.contains("成功"), result0);
                    log.info("独占电梯 <<<");
                    break;
                case MqttConstants.ACTION_ENTER_ELEVATOR://通知机器人进入电梯
                    log.info("通知机器人进入电梯 >>>");
                    String result1 = logicHandler.notifyRobotEnterElevator(mqttMsg);
                    if (!"成功".equals(result1))
                        mqttManager.sendResult(mqttMsg, false, result1);//失败是返回失败原因 转发给机器人之后就不管了
                    log.info("通知机器人进入电梯 <<<");
                    break;
                case MqttConstants.ACTION_SELECT_FLOORS://选层
                    log.info("选层 >>>");
                    String result2 = logicHandler.selectFloor(mqttMsg);
                    mqttManager.sendResult(mqttMsg, result2.contains("成功"), result2);
                    log.info("选层 <<<");
                    break;
                case MqttConstants.ACTION_EXIT_ELEVATOR://通知机器人出去电梯
                    log.info("通知机器人出去电梯 >>>");
                    String result3 = logicHandler.notifyRobotExitElevator(mqttMsg);
                    if (!"成功".equals(result3))
                        mqttManager.sendResult(mqttMsg, false, result3);//失败是返回失败原因 转发给机器人之后就不管了
                    log.info("通知机器人出去电梯 <<<");
                    break;
                case MqttConstants.ACTION_TO_WAITING_POINT://通知机器人去候梯点
                    log.info("通知机器人去候梯点 >>>");
                    String result4 = logicHandler.notifyRobotToWaitingPoint(mqttMsg);
                    if (!"成功".equals(result4))
                        mqttManager.sendResult(mqttMsg, false, result4);//失败是返回失败原因 转发给机器人之后就不管了
                    log.info("通知机器人去候梯点 <<<");
                    break;
                case MqttConstants.ACTION_RELEASE_ELEVATOR://取消独占
                    log.info("取消独占 >>>");
                    String result5 = logicHandler.releaseElevator(mqttMsg);
                    mqttManager.sendResult(mqttMsg, result5.contains("成功"), result5);
                    log.info("取消独占 <<<");
                    break;
                default:
                    break;
            }
        }
    }
}
