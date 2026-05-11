package org.example.demo2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.demo2.bean.OccupyUserInfo;
import org.example.demo2.bean.Result;
import org.example.demo2.bean.UsedRobotInfo;
import org.example.demo2.elevator.ElevatorConnector;
import org.example.demo2.elevator.ElevatorResultHandler;
import org.example.demo2.mqtt.MqttManager;
import org.example.demo2.mqtt.MqttMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 整体的逻辑控制类
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
    private OccupyUserInfo occupyUserInfo = null;

    /**
     * 判断和更新当前 是否有机器人正在 进入电梯,出去电梯,去候梯点,
     * 控制选层?是否也加上?
     */
    private final Object robotUseLock = new Object();
    private final UsedRobotInfo usedRobotInfo = new UsedRobotInfo(null, null, USED_STATUS_NONE);//使用电梯的机器人信息

    public static final int USED_STATUS_NONE = -10000;//有没有机器人正在进行 进入电梯,出去电梯,在电梯中
    public static final int USED_STATUS_ROBOT_INSIDE = 0;//机器人在电梯中
    public static final int USED_STATUS_ROBOT_ENTERING = 1;//机器人在进入电梯中
    public static final int USED_STATUS_ROBOT_TO_WAITING_POINT = -1;//机器人在去候梯点


    public OccupyUserInfo getOccupyUserInfo() {
        synchronized (occupyStatusLock) {
            return occupyUserInfo;
        }
    }

    public UsedRobotInfo getUsedRobotInfo() {
        synchronized (robotUseLock) {
            return usedRobotInfo;
        }
    }

    /**
     * 占用电梯
     */
    public void occupyElevator(MqttMsg originalMsg) {
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
        }
        Result operationResult = checkOccupyElevator(userId, userName);
        mqttManager.sendResult(originalMsg, operationResult.isSuccess(), operationResult.getMsg());
    }

    private Result checkOccupyElevator(String userId, String userName) {
        if (userId == null) return new Result(false, "占用电梯指令执行失败!\nmqtt消息解析失败");
        synchronized (occupyStatusLock) {
            if (occupyUserInfo == null) {
                //setOccupyElevatorUser 为true之后 会在没有给电梯写消息的时候自动给电梯发送独占 防止超1分没给电梯发送独占相关消息 电梯自动超时取消掉。逻辑在OccupyHandler.class
                if (ElevatorConnector.getInstance().setOccupyElevatorUser(true)) {
                    occupyUserInfo = new OccupyUserInfo(userId, userName, System.nanoTime());
                    return new Result(true, "占用电梯指令执行成功!");
                } else return new Result(false, "占用电梯指令执行失败!\n电梯未连接.请检查网络或稍后重试");
            } else if (occupyUserInfo.getUserId().equals(userId)) return new Result(true, "占用电梯指令执行成功");
            else
                return new Result(false, "占用电梯指令执行失败!\n电梯正在被:" + occupyUserInfo.getUserName() + "占用,如需控制请联系" + occupyUserInfo.getUserName() + "取消占用");
        }
    }

    /**
     * 取消占用电梯
     */
    public void releaseElevator(MqttMsg originalMsg) {
        String userId = null;
        try {
            String value = (String) originalMsg.getValue();
            JsonElement jsonElement = JsonParser.parseString(value);// 1. 将 JSON 字符串解析为 JsonElement
            JsonObject jsonObject = jsonElement.getAsJsonObject();// 2. 获取 JsonObject
            userId = jsonObject.get("userId").getAsString();// 3. 解析特定字段
        } catch (Exception e) {
            log.info("解析mqtt失败", e);
        }
        Result releaseResult = checkReleaseElevator(userId);
        mqttManager.sendResult(originalMsg, releaseResult.isSuccess(), releaseResult.getMsg());
    }

    private Result checkReleaseElevator(String userId) {
        if (userId == null) return new Result(false, "取消占用指令执行失败!\nmqtt消息解析失败");
        synchronized (occupyStatusLock) {
            if (occupyUserInfo == null) return new Result(true, "取消占用指令执行失败!\n电梯当前没有被占用");
            else if (!occupyUserInfo.getUserId().equals(userId))
                return new Result(false, "取消占用指令执行失败!\n电梯正在被:" + occupyUserInfo.getUserName() + "占用,如需控制请联系" + occupyUserInfo.getUserName() + "取消占用");
            else {//是自己占用的情况下 检查是否有机器人在电梯内或进出电梯中
                synchronized (robotUseLock) {
                    if (usedRobotInfo.getRobotUsedStatus() != USED_STATUS_NONE) {
                        String s = "";
                        if (usedRobotInfo.getRobotUsedStatus() == USED_STATUS_ROBOT_INSIDE) s = "电梯内";
                        else if (usedRobotInfo.getRobotUsedStatus() == USED_STATUS_ROBOT_ENTERING) s = "进电梯";
                        else if (usedRobotInfo.getRobotUsedStatus() == USED_STATUS_ROBOT_TO_WAITING_POINT)
                            s = "去候梯点";
                        return new Result(false, "取消占用指令执行失败!\n机器人" + usedRobotInfo.getUsedRobotName() + "正在" + s);
                    } else {
                        occupyUserInfo = null;
                        return new Result(true, "取消占用指令执行成功!");
                    }
                }
            }
        }
    }

    /**
     * 选层
     * 只有当前是自己占用时才能选层
     */
    public void selectFloor(MqttMsg originalMsg) {
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
        }
        Result releaseResult = checkSelectFloor(userId, targetFloor);
        mqttManager.sendResult(originalMsg, releaseResult.isSuccess(), releaseResult.getMsg());
    }


    /**
     * 机器人想要进入电梯
     */
    public void robotToEnterElevatorRequest(MqttMsg originalMsg) {
        String robotName = (String) originalMsg.getValue();
        String robotId = originalMsg.getSource();
        if (robotId == null) return;
        synchronized (robotUseLock) {
            if (usedRobotInfo.getUsedRobotId() == null || robotId.equals(usedRobotInfo.getUsedRobotId())) {
                usedRobotInfo.setUsedRobotId(robotId);
                usedRobotInfo.setUsedRobotName(robotName);
                usedRobotInfo.setRobotUsedStatus(USED_STATUS_ROBOT_ENTERING);
            }
        }
    }

    public void robotInElevatorRequest(MqttMsg originalMsg) {
        String robotName = (String) originalMsg.getValue();
        String robotId = originalMsg.getSource();
        if (robotId == null) return;
        synchronized (robotUseLock) {
            if (usedRobotInfo.getUsedRobotId() == null || robotId.equals(usedRobotInfo.getUsedRobotId())) {
                usedRobotInfo.setUsedRobotId(robotId);
                usedRobotInfo.setUsedRobotName(robotName);
                usedRobotInfo.setRobotUsedStatus(USED_STATUS_ROBOT_INSIDE);
            }
        }
    }

    public void robotToWaitingPointRequest(MqttMsg originalMsg) {
        String robotName = (String) originalMsg.getValue();
        String robotId = originalMsg.getSource();
        if (robotId == null) return;
        synchronized (robotUseLock) {
            if (usedRobotInfo.getUsedRobotId() == null || robotId.equals(usedRobotInfo.getUsedRobotId())) {
                usedRobotInfo.setUsedRobotId(robotId);
                usedRobotInfo.setUsedRobotName(robotName);
                usedRobotInfo.setRobotUsedStatus(USED_STATUS_ROBOT_TO_WAITING_POINT);
            }
        }
    }

    public void robotInWaitingPointRequest(MqttMsg originalMsg) {
        String robotName = (String) originalMsg.getValue();
        String robotId = originalMsg.getSource();
        if (robotId == null) return;
        synchronized (robotUseLock) {
            if (usedRobotInfo.getUsedRobotId() == null || robotId.equals(usedRobotInfo.getUsedRobotId())) {
                usedRobotInfo.setUsedRobotId(null);
                usedRobotInfo.setUsedRobotName(null);
                usedRobotInfo.setRobotUsedStatus(USED_STATUS_NONE);
            }
        }
    }


    private Result checkSelectFloor(String userId, int targetFloor) {
        if (userId == null || targetFloor == -9999) return new Result(false, "电梯选层失败!\nmqtt消息解析失败");
        if (!Config.ELEVATOR_FLOORS.contains(targetFloor)) return new Result(false, "电梯选层失败\n目标楼层不可达");
        synchronized (occupyStatusLock) {
            if (occupyUserInfo == null) return new Result(false, "电梯选层失败!\n请先占用电梯");
            else if (!occupyUserInfo.getUserId().equals(userId))
                return new Result(false, "电梯选层失败!\n电梯正在被:" + occupyUserInfo.getUserName() + "占用,如需控制请联系" + occupyUserInfo.getUserName() + "取消占用");
            else {
                boolean isOccupiedSuccess = ElevatorResultHandler.getInstance().checkOccupiedSuccess(occupyUserInfo.getOccupyTime());
                if (!isOccupiedSuccess) return new Result(false, "电梯选层失败!\n独占操作还未完成确认.请稍后重试");
                synchronized (robotUseLock) {
                    if (usedRobotInfo.getRobotUsedStatus() != USED_STATUS_NONE && usedRobotInfo.getRobotUsedStatus() != USED_STATUS_ROBOT_INSIDE) {
                        String s = "";
                        if (usedRobotInfo.getRobotUsedStatus() == USED_STATUS_ROBOT_ENTERING) s = "进电梯";
                        else if (usedRobotInfo.getRobotUsedStatus() == USED_STATUS_ROBOT_TO_WAITING_POINT)
                            s = "去候梯点";
                        return new Result(false, "电梯选层失败!\n机器人" + usedRobotInfo.getUsedRobotName() + "正在" + s);
                    } else {
                        boolean b = ElevatorConnector.getInstance().setSelectFloor(targetFloor);
                        if (b) return new Result(true, "电梯选层" + targetFloor + "F,执行成功");
                        else return new Result(false, "电梯选层失败!\n电梯未连接.请检查网络或稍后重试");
                    }
                }
            }
        }
    }

    /**
     * 平台通知机器人进入电梯
     */
    public void notifyRobotEnterElevator(MqttMsg originalMsg) {
        String userId = null;
        String robotId = null;
        try {
            String value = (String) originalMsg.getValue();
            JsonElement jsonElement = JsonParser.parseString(value);// 1. 将 JSON 字符串解析为 JsonElement
            JsonObject jsonObject = jsonElement.getAsJsonObject();// 2. 获取 JsonObject
            // 3. 解析特定字段
            userId = jsonObject.get("userId").getAsString();
            robotId = jsonObject.get("robotId").getAsString();
        } catch (Exception e) {
            log.info("解析mqtt失败", e);
        }
        Result releaseResult = checkNotifyRobotEnterElevator(originalMsg, userId, robotId);
        if (!releaseResult.isSuccess())//isSuccess 就直接发给机器人了 让机器人给平台回消息 这里只有!isSuccess的时候才给平台回消息
            mqttManager.sendResult(originalMsg, releaseResult.isSuccess(), releaseResult.getMsg());
    }

    private Result checkNotifyRobotEnterElevator(MqttMsg originalMsg, String userId, String robotId) {
        if (userId == null || robotId == null) return new Result(false, "通知机器人进电梯失败,mqtt消息解析失败");
        synchronized (occupyStatusLock) {
            if (occupyUserInfo == null) return new Result(false, "通知机器人进电梯失败,请先占用电梯");
            else if (!occupyUserInfo.getUserId().equals(userId))
                return new Result(false, "通知机器人进电梯失败,当前已占用电梯用户:" + occupyUserInfo.getUserName());
            else {//自己已经占用电梯
                boolean isOccupiedSuccess = ElevatorResultHandler.getInstance().checkOccupiedSuccess(occupyUserInfo.getOccupyTime());
                if (!isOccupiedSuccess)
                    return new Result(false, "通知机器人进电梯失败,独占操作还未完成确认.请稍后重试");
                synchronized (robotUseLock) {
                    if (usedRobotInfo.getUsedRobotId() != null && !usedRobotInfo.getUsedRobotId().equals(robotId)) {
                        String s = "使用电梯";
                        if (usedRobotInfo.getRobotUsedStatus() == USED_STATUS_ROBOT_INSIDE) s = "电梯内";
                        if (usedRobotInfo.getRobotUsedStatus() == USED_STATUS_ROBOT_ENTERING) s = "进电梯";
                        else if (usedRobotInfo.getRobotUsedStatus() == USED_STATUS_ROBOT_TO_WAITING_POINT)
                            s = "去候梯点";
                        return new Result(false, "通知机器人进电梯失败,机器人" + usedRobotInfo.getUsedRobotName() + "正在" + s);
                    }
                }
                mqttManager.forwarderToRobot(originalMsg, robotId);
                return new Result(true, "已通知机器人进电梯");
            }
        }
    }


    /**
     * 通知机器人离开电梯
     */
    public void notifyRobotExitElevator(MqttMsg originalMsg) {
        String userId = null;
        String robotId = null;
        try {
            String value = (String) originalMsg.getValue();
            JsonElement jsonElement = JsonParser.parseString(value);// 1. 将 JSON 字符串解析为 JsonElement
            JsonObject jsonObject = jsonElement.getAsJsonObject();// 2. 获取 JsonObject
            // 3. 解析特定字段
            userId = jsonObject.get("userId").getAsString();
            robotId = jsonObject.get("robotId").getAsString();
        } catch (Exception e) {
            log.info("解析mqtt失败", e);
        }
        Result releaseResult = checkNotifyRobotExitElevator(originalMsg, userId, robotId);
        mqttManager.sendResult(originalMsg, releaseResult.isSuccess(), releaseResult.getMsg());
    }

    private Result checkNotifyRobotExitElevator(MqttMsg originalMsg, String userId, String robotId) {
        if (userId == null || robotId == null) return new Result(false, "通知机器人出电梯失败,mqtt消息解析失败");
        synchronized (occupyStatusLock) {
            if (occupyUserInfo == null) return new Result(false, "通知机器人出电梯失败,请先占用电梯");
            else if (!occupyUserInfo.getUserId().equals(userId))
                return new Result(false, "通知机器人出电梯失败,当前已占用电梯用户:" + occupyUserInfo.getUserName());
            else {//自己已经占用电梯
                boolean isOccupiedSuccess = ElevatorResultHandler.getInstance().checkOccupiedSuccess(occupyUserInfo.getOccupyTime());
                if (!isOccupiedSuccess)
                    return new Result(false, "通知机器人出电梯失败,独占操作还未完成确认.请稍后重试");
                mqttManager.forwarderToRobot(originalMsg, robotId);
                return new Result(true, "已通知机器人出电梯");
            }
        }
    }

    /**
     * 通知机器人去候梯点
     */
    public void notifyRobotToWaitingPoint(MqttMsg originalMsg) {
        String userId = null;
        String robotId = null;
        try {
            String value = (String) originalMsg.getValue();
            JsonElement jsonElement = JsonParser.parseString(value);// 1. 将 JSON 字符串解析为 JsonElement
            JsonObject jsonObject = jsonElement.getAsJsonObject();// 2. 获取 JsonObject
            // 3. 解析特定字段
            userId = jsonObject.get("userId").getAsString();
            robotId = jsonObject.get("robotId").getAsString();
        } catch (Exception e) {
            log.info("解析mqtt失败", e);
        }
        Result releaseResult = checkNotifyRobotToWaitingPoint(originalMsg, userId, robotId);
        mqttManager.sendResult(originalMsg, releaseResult.isSuccess(), releaseResult.getMsg());
    }

    private Result checkNotifyRobotToWaitingPoint(MqttMsg originalMsg, String userId, String robotId) {
        if (userId == null || robotId == null) return new Result(false, "通知机器人去候梯点失败,mqtt消息解析失败");
        synchronized (occupyStatusLock) {
            if (occupyUserInfo == null) return new Result(false, "通知机器人去候梯点失败,请先占用电梯");
            else if (!occupyUserInfo.getUserId().equals(userId))
                return new Result(false, "通知机器人去候梯点失败,当前已占用电梯用户:" + occupyUserInfo.getUserName());
            else {//自己已经占用电梯
                boolean isOccupiedSuccess = ElevatorResultHandler.getInstance().checkOccupiedSuccess(occupyUserInfo.getOccupyTime());
                if (!isOccupiedSuccess)
                    return new Result(false, "通知机器人去候梯点失败,独占操作还未完成确认.请稍后重试");
                mqttManager.forwarderToRobot(originalMsg, robotId);
                return new Result(true, "已通知机器人去候梯点");
            }
        }
    }


}
