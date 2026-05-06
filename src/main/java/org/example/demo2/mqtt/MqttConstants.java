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


    public static final String TYPE_ROBOT_TO_ELEVATOR_REQUEST = "ROBOT_TO_ELEVATOR_REQUEST";//机器人给电梯服务发送请求
    public static final String ACTION_ROBOT_ENTER_ELEVATOR = "ROBOT_ENTER_ELEVATOR";//机器人告诉电梯服务 机器人想要进电梯
    public static final String ACTION_ROBOT_IN_ELEVATOR = "ROBOT_IN_ELEVATOR";//机器人告诉电梯服务 机器人已经在电梯内了
    public static final String ACTION_ROBOT_TO_WAITING_POINT = "ROBOT_TO_WAITING_POINT";//机器人告诉电梯服务 机器人想要去往候梯点
    public static final String ACTION_ROBOT_IN_WAITING_POINT = "ROBOT_IN_WAITING_POINT";//机器人告诉电梯服务 机器人已经到达候梯点



}
