package org.example.demo2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.demo2.elevator.ElevatorConnector;
import org.example.demo2.mqtt.MqttManager;
import org.example.demo2.mqtt.MqttMsg;
import org.example.demo2.mqtt.MqttMsgHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.Arrays;

/**
 * 整体的逻辑控制类
 * //todo jiao mqtt回复失败原因?
 *
 */
public class LogicHandler {
    private static final Logger log = LoggerFactory.getLogger(LogicHandler.class);
    private static final MqttManager mqttManager = MqttManager.getInstance();

    private LogicHandler() {

    }

    private static final class InstanceHolder {
        private static final LogicHandler INSTANCE = new LogicHandler();
    }

    public static LogicHandler getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * 当前占用电梯的用户锁。防止多线程判断错误
     */
    private final Object occupyStatusLock = new Object();

    /**
     * 当前使用占用电梯的用户
     */
    private String[] currentOccupyUserInfo = null;

    /**
     * 获取当前占用电梯的用户
     */
    public String[] getCurrentOccupyElevatorUser() {
        synchronized (occupyStatusLock) {
            return currentOccupyUserInfo;
        }
    }

    /**
     * 占用电梯
     * 只有当前没有用户占用电梯的时候才能占用电梯
     */
    public String occupyElevator(MqttMsg originalMsg) {
        String userId = null;
        String userName = null;
        try {
            String value = (String) originalMsg.getValue();
            JsonElement jsonElement = JsonParser.parseString(value);// 1. 将 JSON 字符串解析为 JsonElement
            JsonObject jsonObject = jsonElement.getAsJsonObject();// 2. 获取 JsonObject
            // 3. 解析特定字段
            userId = jsonObject.get("userId").getAsString();
            userName = jsonObject.get("userName").getAsString();
            if (userName == null || userName.isEmpty()) userName = "未知";
        } catch (Exception e) {
            log.info("解析mqtt失败", e);
            return "占用电梯失败,mqtt消息解析失败";
        }

        synchronized (occupyStatusLock) {
            String[] userInfo = currentOccupyUserInfo;
            if (userInfo == null || userInfo[0].equals(userId)) {
                //之后 在定时发送独占信息 防止超1分每个电梯发送独占相关消息 电梯自动超时取消掉。逻辑在OccupyHandler.class
                boolean b = ElevatorConnector.getInstance().setOccupyElevatorUser(true);
                if (b) {
                    this.currentOccupyUserInfo = new String[]{userId, userName};
                    return "占用电梯,执行成功";
                } else return "占用电梯失败,电梯未连接.请检查网络或稍后重试";
            } else return "占用电梯失败,当前已占用电梯用户:" + userInfo[1];
        }
    }

    /**
     * 取消占用电梯
     * 只有当前是自己占用时才能取消占用
     */
    public String releaseElevator(MqttMsg originalMsg) {
        String userId = null;
        try {
            String value = (String) originalMsg.getValue();
            JsonElement jsonElement = JsonParser.parseString(value);// 1. 将 JSON 字符串解析为 JsonElement
            JsonObject jsonObject = jsonElement.getAsJsonObject();// 2. 获取 JsonObject
            userId = jsonObject.get("userId").getAsString();// 3. 解析特定字段
        } catch (Exception e) {
            log.info("解析mqtt失败", e);
            return "取消占用电梯失败,mqtt消息解析失败";
        }

        synchronized (occupyStatusLock) {
            String[] userInfo = currentOccupyUserInfo;
            if (userInfo == null) {
                return "成功";
            } else {
                if (userInfo[0].equals(userId)) {
                    boolean b = ElevatorConnector.getInstance().setOccupyElevatorUser(false);
                    if (b) {
                        this.currentOccupyUserInfo = null;
                        return "取消占用电梯,执行成功";
                    } else return "取消占用电梯失败,电梯未连接.请检查网络或稍后重试";
                } else return "取消占用电梯失败,当前已占用电梯用户:" + userInfo[1];
            }
        }
    }

    /**
     * 选层
     * 只有当前是自己占用时才能选层
     */
    public String selectFloor(MqttMsg originalMsg) {
        String userId = null;
        int targetFloor = -9999;
        try {
            String value = (String) originalMsg.getValue();
            JsonElement jsonElement = JsonParser.parseString(value);// 1. 将 JSON 字符串解析为 JsonElement
            JsonObject jsonObject = jsonElement.getAsJsonObject();// 2. 获取 JsonObject
            // 3. 解析特定字段
            userId = jsonObject.get("userId").getAsString();
            targetFloor = jsonObject.get("targetFloor").getAsInt();
        } catch (Exception e) {
            log.info("解析mqtt失败", e);
            return "选层失败,mqtt消息解析失败";
        }

        //todo 选层时通过机器人。判断机器人是否在电梯内或候梯点

        if (!Config.ELEVATOR_FLOORS.contains(targetFloor)) return "选层失败,目标楼层不可达";
        synchronized (occupyStatusLock) {
            String[] userInfo = currentOccupyUserInfo;
            if (userInfo == null) return "选层失败,请先占用电梯";
            else if (userInfo[0].equals(userId)) {
                boolean b = ElevatorConnector.getInstance().setSelectFloor(targetFloor);
                if (b) return "选层" + targetFloor + "F,执行成功";
                else return "选层失败,电梯未连接.请检查网络或稍后重试";
            } else return "选层失败,当前已占用电梯用户:" + userInfo[1];
        }
    }

    /**
     * 通知机器人进入电梯
     */
    public String notifyRobotEnterElevator(MqttMsg originalMsg) {
        return "暂不支持此操作";

//        String userId = null;
//        String robotId = null;
//        try {
//            String value = (String) originalMsg.getValue();
//            JsonElement jsonElement = JsonParser.parseString(value);// 1. 将 JSON 字符串解析为 JsonElement
//            JsonObject jsonObject = jsonElement.getAsJsonObject();// 2. 获取 JsonObject
//            // 3. 解析特定字段
//            userId = jsonObject.get("userId").getAsString();
//            robotId = jsonObject.get("robotId").getAsString();
//        } catch (Exception e) {
//            log.info("解析mqtt失败", e);
//            return "通知机器人进入电梯失败,mqtt消息解析失败";
//        }
//
//        synchronized (occupyStatusLock) {
//            String[] userInfo = currentOccupyUserInfo;
//            if (userInfo == null) return "通知机器人进入电梯失败,请先占用电梯";
//            else {
//                if (userInfo[0].equals(userId)) {
//                    mqttManager.forwarderToRobot(originalMsg, robotId);
//                    return "成功";
//                } else return "通知机器人进入电梯失败,当前已占用电梯用户:" + userInfo[1];
//            }
//        }
    }

    /**
     * 通知机器人离开电梯
     */
    public String notifyRobotExitElevator(MqttMsg originalMsg) {
        return "暂不支持此操作";
//        String userId = null;
//        String robotId = null;
//        try {
//            String value = (String) originalMsg.getValue();
//            JsonElement jsonElement = JsonParser.parseString(value);// 1. 将 JSON 字符串解析为 JsonElement
//            JsonObject jsonObject = jsonElement.getAsJsonObject();// 2. 获取 JsonObject
//            // 3. 解析特定字段
//            userId = jsonObject.get("userId").getAsString();
//            robotId = jsonObject.get("robotId").getAsString();
//        } catch (Exception e) {
//            log.info("解析mqtt失败", e);
//            return "通知机器人离开电梯失败,mqtt消息解析失败";
//        }
//
//        synchronized (occupyStatusLock) {
//            String[] userInfo = currentOccupyUserInfo;
//            if (userInfo == null) return "通知机器人离开电梯,请先占用电梯";
//            else {
//                if (userInfo[0].equals(userId)) {
//                    mqttManager.forwarderToRobot(originalMsg, robotId);
//                    return "成功";
//                } else return "通知机器人离开电梯,当前已占用电梯用户:" + userInfo[1];
//            }
//        }
    }

    /**
     * 停止机器人去候梯点
     */
    public String notifyRobotToWaitingPoint(MqttMsg originalMsg) {
        return "暂不支持此操作";
//        String userId = null;
//        String userName = null;
//        String robotId = null;
//        try {
//            String value = (String) originalMsg.getValue();
//            JsonElement jsonElement = JsonParser.parseString(value);// 1. 将 JSON 字符串解析为 JsonElement
//            JsonObject jsonObject = jsonElement.getAsJsonObject();// 2. 获取 JsonObject
//            // 3. 解析特定字段
//            userId = jsonObject.get("userId").getAsString();
//            userName = jsonObject.get("userName").getAsString();
//            robotId = jsonObject.get("robotId").getAsString();
//        } catch (Exception e) {
//            log.info("解析mqtt失败", e);
//            return "停止机器人去候梯点失败,mqtt消息解析失败";
//        }
//
//        synchronized (occupyStatusLock) {
//            String[] userInfo = currentOccupyUserInfo;
//            if (userInfo == null) return "通知机器人进入电梯失败,请先占用电梯";
//            else {
//                if (userInfo[0].equals(userId)) {
//                    mqttManager.forwarderToRobot(originalMsg, robotId);
//                    return "成功";
//                } else return "通知机器人进入电梯失败,当前已占用电梯用户:" + userInfo[1];
//            }
//        }
    }


}
