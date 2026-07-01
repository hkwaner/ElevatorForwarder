package org.example.demo2;

import java.util.Arrays;
import java.util.List;

/**
 * 配置工具类 - 静态访问，开箱即用
 */
public class Config {
    //电梯配置 (Netty TCP 服务端，5G CPE 作为客户端连接到此端口)
    public static final int ELEVATOR_PORT = 20108;//电梯监听端口（5G CPE连接到此端口）

    //电梯可用的楼层配置
//    public static final List<Integer> ELEVATOR_FLOORS = Arrays.asList(1,2,3,4,5);
//    public static final List<Integer> ELEVATOR_FLOORS = Arrays.asList(1,3,4);//高新材料    1-4层 134可以用 2层没轨道
    public static final List<Integer> ELEVATOR_FLOORS = Arrays.asList(1,2,3,4);//黎明化工    1-4层

    //MQTT 配置
//    public static final String MQTT_URL = "tcp://192.168.8.3:1883";//干燥一期 广拓能源
    public static final String MQTT_URL = "tcp://192.168.8.4:1883";//干燥二期 高新材料
    public static final String MQTT_CLIENT_ID = "elevator_proxy";
    public static final String MQTT_TOPIC1 = "topic-insbot";

    //目标电梯设备地址    /todo 暂时是固定的 多电梯场景兼容
    public static final byte elevatorAddress = (byte) 0xA1;

}
