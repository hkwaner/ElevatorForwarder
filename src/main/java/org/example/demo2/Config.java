package org.example.demo2;

import java.util.Arrays;
import java.util.List;

/**
 * 配置工具类 - 静态访问，开箱即用
 */
public class Config {
    //电梯配置 (Netty TCP)
//    public static final String ELEVATOR_HOST = "192.168.8.80";//电梯ip 干燥一期 广拓能源    1-5层
        public static final  String ELEVATOR_HOST = "192.168.8.81";//电梯ip 干燥二期 高新材料    1-4层 134可以用 2层没轨道
    public static final int ELEVATOR_PORT = 20108;//电梯端口
    //电梯可用的楼层配置
//    public static final List<Integer> ELEVATOR_FLOORS = Arrays.asList(1,2,3,4,5);
    public static final List<Integer> ELEVATOR_FLOORS = Arrays.asList(1,3,4);//高新材料    1-4层 134可以用 2层没轨道

    //MQTT 配置
//    public static final String MQTT_URL = "tcp://192.168.8.3:1883";//干燥一期 广拓能源
    public static final String MQTT_URL = "tcp://192.168.8.4:1883";//干燥二期 高新材料
    public static final String MQTT_CLIENT_ID = "elevator_proxy";
    public static final String MQTT_TOPIC1 = "topic-insbot";


}
