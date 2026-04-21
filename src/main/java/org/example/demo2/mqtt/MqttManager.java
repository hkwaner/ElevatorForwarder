package org.example.demo2.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.example.demo2.Config;
import org.example.demo2.elevator.ElevatorResult;
import org.example.demo2.utils.HashMapQueue;
import org.example.demo2.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * mqtt消息管理器 - 通过 MQTT 与多个机器人或管理平台通信
 * todo jiao 居然不需要用户名密码?
 */
public class MqttManager {
    private static final Logger log = LoggerFactory.getLogger(MqttManager.class);

    // QOS 0 消息最多交付一次，可能会丢失
    // QOS 1 消息至少交付一次，保证消息会被收到，但可能出现重复
    private static final int QOS_0 = 0;
    private final HashMapQueue<Long, MqttMsg> mCachedReceiveMessages = new HashMapQueue<>(100);

    private MqttClient mqttClient;

    private MqttManager() {
    }

    private static final class InstanceHolder {
        private static final MqttManager INSTANCE = new MqttManager();
    }

    public static MqttManager getInstance() {
        return InstanceHolder.INSTANCE;
    }

    ExecutorService pool = new ThreadPoolExecutor(
            4,                          // core（CPU核数）
            8,                          // max
            60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000),  // 有界队列（关键）
            new ThreadPoolExecutor.CallerRunsPolicy() // 限流?
    );

    private volatile boolean runFlag = true;

    private boolean isConnect() {
        return mqttClient != null && mqttClient.isConnected();
    }

    /**
     * 启动 MQTT 客户端
     */
    public void start() {
        log.info("[MQTT] 启动连接线程");
        pool.execute(this::connectLoop);
    }

    /**
     * 订阅机器人主题
     */
    private void subscribeTopics() {
        String[] topics = new String[]{"topic-insbot","topic-elevator"};
        int[] pos = new int[]{QOS_0,QOS_0};

        if (!isConnect()) {
            log.info("[MQTT] subscribeTopics 失败,未连接");
            return;
        }

        log.info("[MQTT] subscribe topics:{}", Arrays.toString(topics));

        try {
            mqttClient.subscribe(topics, pos);
            log.info("[MQTT] 订阅成功");
        } catch (Exception ex) {
            log.info("[MQTT] 订阅失败", ex);
        }

    }

    /**
     * 处理收到的 MQTT 消息
     */
    private void handleIncomingMessage(String topic, MqttMessage message) {
        String payloadStr = new String(message.getPayload());
        MqttMsg mqttMsg = null;
        try {
            mqttMsg = JsonUtils.getGson().fromJson(payloadStr, MqttMsg.class);
        } catch (Exception e) {
            log.info("[MQTT] 解析mqtt消息失败", e);
        }
        if (mqttMsg == null) return;
        String target = mqttMsg.getTarget();
        if (target == null || target.isEmpty() || !target.equals(Config.MQTT_CLIENT_ID)) return;//目标不是自己的就忽略掉
        String source = mqttMsg.getSource();
        if (source == null || source.isEmpty() || source.equals(Config.MQTT_CLIENT_ID)) return;//来源是自己的消息忽略掉

        log.info("[MQTT] handleIncomingMessage   topic:" + topic + "   message:" + payloadStr);


        if (mCachedReceiveMessages.add(mqttMsg.getId() != 0 ? mqttMsg.getId() : mqttMsg.getTimeStamp(), mqttMsg)) {
            pool.execute(new MqttMsgHandler(topic, mqttMsg));
        } else log.info("[MQTT] repeat mqtt message , ignore");
    }

    public void broadcastElevatorResult(ElevatorResult elevatorResult) {
        if (!isConnect()) return;
        MqttMsg msg = new MqttMsg();
        msg.setSource(Config.MQTT_CLIENT_ID);
        msg.setTarget("subscribers");
        msg.setType(MqttConstants.TYPE_ELEVATOR_BROADCAST_INFO);
        msg.setAction(MqttConstants.ACTION_ELEVATOR_BASE_INFO);
        String json = JsonUtils.getGson().toJson(elevatorResult, ElevatorResult.class);
        msg.setValue(json);

        //示例参考readme.md

        MqttMessage message = new MqttMessage();
        String msgJson = JsonUtils.getGson().toJson(msg, MqttMsg.class);
        message.setPayload(msgJson.getBytes());
        message.setQos(QOS_0);
        log.info("broadcastElevatorResult send message:{}", msgJson);
        try {
            mqttClient.publish("topic-insbot", message);
        } catch (MqttException e) {
            log.info("[MQTT] publishElevatorResult msgJson:{} error:", msgJson, e);
        }
    }

    public void sendResult(MqttMsg originalMsg, boolean success, String value) {
        MqttMsg msg = new MqttMsg();
        msg.setSource(Config.MQTT_CLIENT_ID);
        msg.setTarget(originalMsg.getSource());
        msg.setType(MqttConstants.TYPE_RESULT);
        msg.setAction(success ? MqttConstants.ACTION_RESULT_SUCCESS : MqttConstants.ACTION_RESULT_FAIL);
        msg.setValue(value);

        msg.setOriginalType(originalMsg.getType());
        msg.setOriginalAction(originalMsg.getAction());

        MqttMessage message = new MqttMessage();
        String msgJson = JsonUtils.getGson().toJson(msg, MqttMsg.class);
        message.setPayload(msgJson.getBytes());
        message.setQos(QOS_0);
        log.info("sendResult send message:{}", msgJson);
        try {
            mqttClient.publish("topic-insbot", message);
        } catch (MqttException e) {
            log.info("[MQTT] publishResult msgJson:{} error:", msgJson, e);
        }
    }

    public void forwarderToRobot(MqttMsg originalMsg, String robotId) {
        MqttMsg msg = new MqttMsg();
        msg.setSource(Config.MQTT_CLIENT_ID);
        msg.setTarget(robotId);
        msg.setType(originalMsg.getType());
        msg.setAction(originalMsg.getAction());
        String valueJson = "{ \"originalSource\":\"" + originalMsg.getSource() + "\"}";
        msg.setValue(valueJson);

        MqttMessage message = new MqttMessage();
        String msgJson = JsonUtils.getGson().toJson(msg, MqttMsg.class);
        message.setPayload(msgJson.getBytes());
        message.setQos(QOS_0);
        log.info("notifyRobotEnterOrExitElevator send message:{}", msgJson);
        try {
            mqttClient.publish("topic-insbot", message);
        } catch (MqttException e) {
            log.info("[MQTT] notifyRobotEnterOrExitElevator msgJson:{} error:", msgJson, e);
        }
    }

    /**
     * 停止
     */
    public void stop() {
        runFlag = false;
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                log.info("[MQTT] 已断开连接");
            } catch (MqttException e) {
                log.info("[MQTT] 断开失败：", e);
            }
        }
    }

    private void connectLoop() {
        try {
            mqttClient = new MqttClient(Config.MQTT_URL, Config.MQTT_CLIENT_ID, new MemoryPersistence());
        } catch (MqttException e) {
            log.info("[MQTT] 初始化实例失败 e:{}", String.valueOf(e));
        }

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(10);
        options.setMaxReconnectDelay(5 * 1000);

        // 设置回调
        mqttClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                log.info("[MQTT] 连接到 Broker: " + serverURI);
                subscribeTopics();
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.info("[MQTT] 与 Broker 连接断开：", cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                handleIncomingMessage(topic, message);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 消息发送完成回调（可选实现）
            }
        });

        while (runFlag && !mqttClient.isConnected()) {
            try {
                mqttClient.connect(options);
                log.info("[MQTT] 客户端已启动，Broker: " + Config.MQTT_URL);
            } catch (MqttException e) {
                log.info("[MQTT] 启动失败", e);
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException ex) {
                    log.info("[MQTT] 启动失败 runFlag:{}", runFlag, ex);
                }
            }
        }

        String t = mqttClient == null ? "null" : "connect:" + mqttClient.isConnected();
        log.info("[MQTT] 结束 runFlag:{} client:{}", runFlag, t);

    }

    public static void main(String[] args) {
        MqttManager instance = MqttManager.getInstance();
        instance.start();
    }


}
