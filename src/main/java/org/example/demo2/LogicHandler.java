package org.example.demo2;

import org.example.demo2.elevator.ElevatorConnector;
import org.example.demo2.mqtt.MqttManager;
import org.example.demo2.mqtt.MqttMsg;

/**
 * 整体的逻辑控制类
 * //todo jiao mqtt回复失败原因?
 *
 */
public class LogicHandler {
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
    private String currentOccupyElevatorUser = null;

    /**
     * 获取当前占用电梯的用户
     */
    public String getCurrentOccupyElevatorUser() {
        synchronized (occupyStatusLock) {
            return currentOccupyElevatorUser;
        }
    }

    /**
     * 占用电梯
     * 只有当前没有用户占用电梯的时候才能占用电梯
     */
    public boolean occupyElevator(String user) {
        synchronized (occupyStatusLock) {
            if (this.currentOccupyElevatorUser == null || !this.currentOccupyElevatorUser.equals(user)) {
                this.currentOccupyElevatorUser = user;
                //todo jiao 定时发送独占信息 防止电梯 自动超市取消掉
                return ElevatorConnector.getInstance().setOccupyElevatorUser(true);
            }
        }
        return false;
    }

    /**
     * 取消占用电梯
     * 只有当前是自己占用时才能取消占用
     */
    public boolean releaseElevator(String user) {
        synchronized (occupyStatusLock) {
            if (user.equals(this.currentOccupyElevatorUser)) {
                this.currentOccupyElevatorUser = null;
                return ElevatorConnector.getInstance().setOccupyElevatorUser(false);
            }
        }
        return false;
    }

    /**
     * 选层
     * 只有当前是自己占用时才能选层
     */
    public boolean selectFloor(String user, int floor) {
        if (!Config.ELEVATOR_FLOORS.contains(floor)) return false;
        synchronized (occupyStatusLock) {
            if (this.currentOccupyElevatorUser != null && this.currentOccupyElevatorUser.equals(user)) {
                return ElevatorConnector.getInstance().setSelectFloor(floor);
            }
        }
        return false;
    }

    /**
     * 通知机器人进入电梯
     */
    public boolean notifyRobotEnterElevator(MqttMsg originalMsg) {
        synchronized (occupyStatusLock) {
            if (this.currentOccupyElevatorUser != null && this.currentOccupyElevatorUser.equals(originalMsg.getSource())) {
                mqttManager.notifyRobotEnterOrExitElevator(originalMsg);
                return true;
            }
        }
        return false;
    }

    /**
     * 通知机器人离开电梯
     */
    public boolean notifyRobotExitElevator(MqttMsg originalMsg) {
        synchronized (occupyStatusLock) {
            if (this.currentOccupyElevatorUser != null && this.currentOccupyElevatorUser.equals(originalMsg.getSource())) {
                mqttManager.notifyRobotEnterOrExitElevator(originalMsg);
                return true;
            }
        }
        return false;
    }


}
