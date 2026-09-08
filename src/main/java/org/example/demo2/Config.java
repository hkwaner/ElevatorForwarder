package org.example.demo2;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * 配置工具类 - 静态访问，开箱即用
 */
public class Config {
    //程序版本号,发布时同步更新;启动时打印(见 MainServer.start)
    public static final String APP_VERSION = "1.0.0";

    //电梯配置 (Netty TCP)
    public static final int ELEVATOR_PORT = 20108;//电梯端口
//    public static final String ELEVATOR_HOST = "192.168.8.80";//电梯ip 干燥一期 广拓能源    1-5层
    public static final  String ELEVATOR_HOST = "192.168.8.81";//电梯ip 干燥二期 高新材料    1-4层 134可以用 2层没轨道
//    public static final  String ELEVATOR_HOST = "192.168.10.31";//电梯ip 北京测试模拟

    //电梯可用的楼层配置
//    public static final List<Integer> ELEVATOR_FLOORS = Arrays.asList(1,2,3,4,5);//广拓能源
    public static final List<Integer> ELEVATOR_FLOORS = Arrays.asList(1,3,4);//高新材料    1-4层 134可以用 2层没轨道

    //MQTT 配置
//    public static final String MQTT_URL = "tcp://192.168.8.3:1883";//干燥一期 广拓能源
    public static final String MQTT_URL = "tcp://192.168.8.4:1883";//干燥二期 高新材料
//    public static final String MQTT_URL = "tcp://192.168.10.94:1883";//北京测试
    public static final String MQTT_CLIENT_ID = "elevator_proxy";
    public static final String MQTT_TOPIC1 = "topic-insbot";

    //平台HTTP报警配置(与机器人一致,通过HTTP上报报警到平台)
//    public static final String PLATFORM_BASE_URL = "http://192.168.8.3:8080";//平台地址
    public static final String PLATFORM_BASE_URL = "http://192.168.8.4:8080";//平台地址
//    public static final String PLATFORM_BASE_URL = "http://192.168.10.94:8080";//平台地址(局域网,需按现场平台地址修改)

//    public static final String ALARM_ROBOT_ID = "FCICA-FB260016";//广拓能源下层机器人
    public static final String ALARM_ROBOT_ID = "FCICA-FB260003";//高新材料下层机器人
//    public static final String ALARM_ROBOT_ID = "FLADY-FB120005";//上报报警使用的机器人ID(平台按此定位项目,需配置为现场实际机器人ID)

    //工作模式(就地/远程)持久化文件路径:服务重启后恢复上次模式,避免掉电/重启后被重置。
    // 固定存到 jar 同级目录的一个隐藏文件,无需额外参数。
    public static final String WORK_MODE_STORE_FILE = computeWorkModeFile();

    /**
     * 计算 jar 同级目录的工作模式文件路径。
     * 通过本类 CodeSource 定位 jar(或 classes)真实位置,取其父目录;不依赖当前工作目录。
     */
    private static String computeWorkModeFile() {
        try {
            URL location = Config.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null && "file".equalsIgnoreCase(location.getProtocol())) {
                Path dir = null;
                try {
                    dir = Paths.get(location.toURI()).getParent();
                } catch (Exception e) {
                    dir = Paths.get(location.getPath()).getParent();
                }
                if (dir != null) {
                    return dir.resolve(".elevator_forwarder_work_mode").toString();
                }
            }
        } catch (Exception ignored) {
            // 定位失败时回退 user.home,避免空路径
        }
        return System.getProperty("user.home") + "/.elevator_forwarder_work_mode";
    }

}
