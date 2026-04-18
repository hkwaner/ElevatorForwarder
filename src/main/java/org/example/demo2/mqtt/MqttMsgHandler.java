package org.example.demo2.mqtt;

import org.example.demo2.LogicHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        switch (mqttMsg.getType()) {
            case MqttConstants.TYPE_ELEVATOR_CONTROL:
                switch (mqttMsg.getAction()) {
                    case MqttConstants.ACTION_OCCUPY_ELEVATOR://独占电梯
                        mqttManager.sendResult(mqttMsg, logicHandler.occupyElevator(source));
                        break;
                    case MqttConstants.ACTION_ENTER_ELEVATOR://进入电梯
                        boolean flag1 = logicHandler.notifyRobotEnterElevator(mqttMsg);//todo jiao 检查如果有问题 不能给机器人发消息 记得 回复下原因???
                        if (!flag1) mqttManager.sendResult(mqttMsg, false);
                        break;
                    case MqttConstants.ACTION_SELECT_FLOORS://选层
                        Object value = mqttMsg.getValue();
                        int intValue = 0;
                        if (value instanceof Number) intValue = ((Number) value).intValue();
//                        else if (value instanceof String) intValue = Integer.parseInt((String) value);
                        else {
                            log.info("value 不是数字类型: {}", value);
                            mqttManager.sendResult(mqttMsg, false);
                            break;
                        }
                        mqttManager.sendResult(mqttMsg, logicHandler.selectFloor(source, intValue));
                        break;
                    case MqttConstants.ACTION_EXIT_ELEVATOR://出去电梯
                        boolean flag2 = logicHandler.notifyRobotExitElevator(mqttMsg);
                        if (!flag2) mqttManager.sendResult(mqttMsg, false);//只有在转发失败的时候才回复失败。转发成功后由机器人处理
                        break;
                    case MqttConstants.ACTION_RELEASE_ELEVATOR://取消独占
                        mqttManager.sendResult(mqttMsg, logicHandler.releaseElevator(source));
                        break;
                    default:
                        break;
                }
                break;
            default:
                break;
        }
    }
}
