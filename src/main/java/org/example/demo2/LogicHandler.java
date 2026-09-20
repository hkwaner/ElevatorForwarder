package org.example.demo2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.demo2.bean.OccupyUserInfo;
import org.example.demo2.bean.Result;
import org.example.demo2.elevator.ElevatorConnector;
import org.example.demo2.elevator.ElevatorResultHandler;
import org.example.demo2.mqtt.MqttManager;
import org.example.demo2.mqtt.MqttMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

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

    // 工作模式:远程(默认,程序独占电梯)/就地(程序释放独占,现场人工操作)
    public static final String WORK_MODE_REMOTE = "REMOTE";
    public static final String WORK_MODE_LOCAL = "LOCAL";
    private volatile String workMode = WORK_MODE_REMOTE;

    public String getWorkMode() {
        return workMode;
    }

    public boolean isLocal() {
        return WORK_MODE_LOCAL.equals(workMode);
    }

    public boolean isRemote() {
        return WORK_MODE_REMOTE.equals(workMode);
    }

    private volatile boolean workModeLoaded = false;

    /**
     * 启动时从持久化文件恢复上次工作模式,须在连接电梯之前调用一次。
     * 若恢复为就地模式,立即置物理独占为不可用,避免程序启动即抢占现场手控面板。
     * 幂等,已加载后重复调用不生效。
     */
    public void initWorkModeFromDisk() {
        if (workModeLoaded) return;
        synchronized (occupyStatusLock) {
            if (workModeLoaded) return;
            String saved = readPersistedWorkMode();
            if (WORK_MODE_LOCAL.equals(saved)) {
                this.workMode = WORK_MODE_LOCAL;
                ElevatorConnector.getInstance().setOccupyEnabled(false);
                log.info("[工作模式] 从持久化恢复:就地模式,不独占电梯");
            } else {
                this.workMode = WORK_MODE_REMOTE;
                log.info("[工作模式] 从持久化恢复:远程模式,保持独占");
            }
            workModeLoaded = true;
        }
    }

    private String readPersistedWorkMode() {
        try {
            Path path = Paths.get(Config.WORK_MODE_STORE_FILE);
            if (!Files.exists(path)) return WORK_MODE_REMOTE;
            String value = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
            return (WORK_MODE_REMOTE.equals(value) || WORK_MODE_LOCAL.equals(value))
                    ? value : WORK_MODE_REMOTE;
        } catch (Exception e) {
            log.warn("[工作模式] 读取持久化文件失败,默认远程模式:{}", e.getMessage());
            return WORK_MODE_REMOTE;
        }
    }

    private void persistWorkMode(String mode) {
        try {
            Path path = Paths.get(Config.WORK_MODE_STORE_FILE);
            Path tmp = Paths.get(Config.WORK_MODE_STORE_FILE + ".tmp");
            Files.write(tmp, mode.getBytes(StandardCharsets.UTF_8));
            // 同目录 rename,基本原子;落到目标文件前临时文件避免写一半被读
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // 持久化失败不阻断本次切换,仅告警
            log.warn("[工作模式] 持久化工作模式失败(本次切换仍生效):{}", e.getMessage());
        }
    }


    public OccupyUserInfo getOccupyUserInfo() {
        synchronized (occupyStatusLock) {
            return occupyUserInfo;
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
        if (isLocal()) return new Result(false, "占用电梯指令执行失败!\n当前为就地模式,远程控制不可用");
        if (userId == null) return new Result(false, "占用电梯指令执行失败!\nmqtt消息解析失败");
        synchronized (occupyStatusLock) {
            if (occupyUserInfo == null) {
                // 物理独占由转发程序从启动起常驻持有,这里只登记当前逻辑属主
                occupyUserInfo = new OccupyUserInfo(userId, userName, System.nanoTime());
                return new Result(true, "占用电梯指令执行成功!");
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
        if (isLocal()) return new Result(false, "取消占用指令执行失败!\n当前为就地模式,远程控制不可用");
        if (userId == null) return new Result(false, "取消占用指令执行失败!\nmqtt消息解析失败");
        synchronized (occupyStatusLock) {
            if (occupyUserInfo == null) return new Result(true, "取消占用指令执行失败!\n电梯当前没有被占用");
            else if (!occupyUserInfo.getUserId().equals(userId))
                return new Result(false, "取消占用指令执行失败!\n电梯正在被:" + occupyUserInfo.getUserName() + "占用,如需控制请联系" + occupyUserInfo.getUserName() + "取消占用");
            else {//是自己占用的情况下 取消逻辑独占(物理独占仍由转发程序常驻持有)
                occupyUserInfo = null;
                return new Result(true, "取消占用指令执行成功!");
            }
        }
    }

    /**
     * 切换工作模式(就地/远程),由网页端通过MQTT下发。
     * 就地:释放物理独占让现场面板可人工选层,并清空逻辑属主,拒绝所有远程控制消息。
     * 远程:重新申请物理独占并恢复远程控制。
     */
    public void switchWorkMode(MqttMsg originalMsg) {
        String mode = null;
        try {
            String value = (String) originalMsg.getValue();
            JsonElement jsonElement = JsonParser.parseString(value);
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            mode = jsonObject.get("mode").getAsString();
        } catch (Exception e) {
            log.info("解析mqtt失败", e);
        }
        if (mode == null || (!WORK_MODE_REMOTE.equals(mode) && !WORK_MODE_LOCAL.equals(mode))) {
            mqttManager.sendResult(originalMsg, false, "切换工作模式失败!\nmqtt消息解析失败或mode值不合法");
            return;
        }
        synchronized (occupyStatusLock) {
            this.workMode = mode;
            if (WORK_MODE_LOCAL.equals(mode)) {
                // 就地:清空逻辑属主并释放物理独占,现场面板可人工选层
                occupyUserInfo = null;
                String msg = "切换工作模式成功!当前为就地模式,远程控制不可用";
                log.info("[工作模式] 切换为就地模式,释放独占,断开远程控制");
                ElevatorConnector.getInstance().setOccupyEnabled(false);
                persistWorkMode(mode);
                mqttManager.sendResult(originalMsg, true, msg);
            } else {
                String msg = "切换工作模式成功!当前为远程模式,程序保持独占";
                log.info("[工作模式] 切换为远程模式,申请独占,恢复远程控制");
                ElevatorConnector.getInstance().setOccupyEnabled(true);
                persistWorkMode(mode);
                mqttManager.sendResult(originalMsg, true, msg);
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


    private Result checkSelectFloor(String userId, int targetFloor) {
        if (isLocal()) return new Result(false, "电梯选层失败!\n当前为就地模式,远程控制不可用");
        if (userId == null || targetFloor == -9999) return new Result(false, "电梯选层失败!\nmqtt消息解析失败");
        if (!Config.ELEVATOR_FLOORS.contains(targetFloor)) return new Result(false, "电梯选层失败\n目标楼层不可达");
        synchronized (occupyStatusLock) {
            if (occupyUserInfo == null) return new Result(false, "电梯选层失败!\n请先占用电梯");
            else if (!occupyUserInfo.getUserId().equals(userId))
                return new Result(false, "电梯选层失败!\n电梯正在被:" + occupyUserInfo.getUserName() + "占用,如需控制请联系" + occupyUserInfo.getUserName() + "取消占用");
            else {
                boolean isOccupiedSuccess = ElevatorResultHandler.getInstance().checkOccupiedSuccess(occupyUserInfo.getOccupyTime());
                if (!isOccupiedSuccess) return new Result(false, "电梯选层失败!\n独占操作还未完成确认.请稍后重试");
                boolean b = ElevatorConnector.getInstance().setSelectFloor(targetFloor);
                if (b) return new Result(true, "电梯选层" + targetFloor + "F,执行成功");
                else return new Result(false, "电梯选层失败!\n电梯未连接.请检查网络或稍后重试");
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
        if (isLocal()) return new Result(false, "通知机器人进电梯失败!\n当前为就地模式,远程控制不可用");
        if (userId == null || robotId == null) return new Result(false, "通知机器人进电梯失败,mqtt消息解析失败");
        synchronized (occupyStatusLock) {
            if (occupyUserInfo == null) return new Result(false, "通知机器人进电梯失败,请先占用电梯");
            else if (!occupyUserInfo.getUserId().equals(userId))
                return new Result(false, "通知机器人进电梯失败,当前已占用电梯用户:" + occupyUserInfo.getUserName());
            else {//自己已经占用电梯
                boolean isOccupiedSuccess = ElevatorResultHandler.getInstance().checkOccupiedSuccess(occupyUserInfo.getOccupyTime());
                if (!isOccupiedSuccess)
                    return new Result(false, "通知机器人进电梯失败,独占操作还未完成确认.请稍后重试");
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
        if (isLocal()) return new Result(false, "通知机器人出电梯失败!\n当前为就地模式,远程控制不可用");
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
        if (isLocal()) return new Result(false, "通知机器人去候梯点失败!\n当前为就地模式,远程控制不可用");
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
