package org.example.demo2.mqtt;

public class MqttConstants {
    public static final String TYPE_ELEVATOR_CONTROL = "ELEVATOR_CONTROL";
    public static final String ACTION_OCCUPY_ELEVATOR = "OCCUPY_ELEVATOR";//独占
    public static final String ACTION_ENTER_ELEVATOR = "ENTER_ELEVATOR";//通知机器人进入电梯
    public static final String ACTION_SELECT_FLOORS = "SELECT_FLOORS";//选层
    public static final String ACTION_EXIT_ELEVATOR = "EXIT_ELEVATOR";//通知机器人出去电梯
    public static final String ACTION_RELEASE_ELEVATOR = "RELEASE_ELEVATOR";//取消独占
    public static final String ACTION_TO_WAITING_POINT = "ACTION_TO_WAITING_POINT";//通知机器人去候梯点

    public static final String TYPE_ELEVATOR_BROADCAST_INFO = "ELEVATOR_BROADCAST_INFO";
    public static final String ACTION_ELEVATOR_BASE_INFO = "ELEVATOR_BASE_INFO";

    public static final String TYPE_RESULT = "RESULT";
    public static final String ACTION_RESULT_SUCCESS = "RESULT_SUCCESS";
    public static final String ACTION_RESULT_FAIL = "RESULT_FAIL";



}
