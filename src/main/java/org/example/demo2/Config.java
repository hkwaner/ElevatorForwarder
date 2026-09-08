package org.example.demo2;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置工具类 - 静态访问，开箱即用
 */
public class Config {
    //程序版本号,发布时同步更新;启动时打印(见 MainServer.start)
    public static final String APP_VERSION = "1.0.0";

    //==========================================================================
    // 现场/环境配置 —— 每个现场对应整套配置(梯IP+可用楼层+MQTT+平台报警)。
    // 切换现场: 只需改下面 ACTIVE_SITE 为对应现场枚举的常量即可。
    // 新增现场: 在 Site 枚举里加一项,填全配套值即可。
    //==========================================================================
    public static final Site ACTIVE_SITE = Site.BEIJING_TS;

    //可选现场清单(此时上下选项要以哪个为准需开发自行确认)
    public enum Site {
        GANZAO_1("干燥一期·广拓能源", "192.168.8.80",  new int[]{1,2,3,4,5}, "tcp://192.168.8.3:1883",  "http://192.168.8.3:8080",  "FCICA-FB260016"),
        GANZAO_2("干燥二期·高新材料", "192.168.8.81",  new int[]{1,3,4},    "tcp://192.168.8.4:1883",  "http://192.168.8.4:8080",  "FCICA-FB260003"),
        BEIJING_TS("北京测试模拟",    "192.168.10.31", new int[]{1,2,3,4,5}, "tcp://192.168.10.94:1883","http://192.168.10.94:8080","FLADY-FB120005");

        private final String desc;
        private final String elevatorHost;
        private final int[] floors;
        private final String mqttUrl;
        private final String platformBaseUrl;
        private final String alarmRobotId;

        Site(String desc, String elevatorHost, int[] floors, String mqttUrl,
             String platformBaseUrl, String alarmRobotId) {
            this.desc = desc;
            this.elevatorHost = elevatorHost;
            this.floors = floors;
            this.mqttUrl = mqttUrl;
            this.platformBaseUrl = platformBaseUrl;
            this.alarmRobotId = alarmRobotId;
        }

        public String getDesc() { return desc; }
        public String getElevatorHost() { return elevatorHost; }
        public int[] getFloors() { return floors; }
        public String getMqttUrl() { return mqttUrl; }
        public String getPlatformBaseUrl() { return platformBaseUrl; }
        public String getAlarmRobotId() { return alarmRobotId; }
    }

    //电梯配置 (Netty TCP)
    public static final int ELEVATOR_PORT = 20108;//电梯端口(各现场一致)
    public static final String ELEVATOR_HOST = ACTIVE_SITE.getElevatorHost();//当前现场电梯IP
    public static final List<Integer> ELEVATOR_FLOORS = listOf(ACTIVE_SITE.getFloors());//当前现场可用楼层

    //MQTT 配置
    public static final String MQTT_URL = ACTIVE_SITE.getMqttUrl();//当前现场MQTT
    public static final String MQTT_CLIENT_ID = "elevator_proxy";
    public static final String MQTT_TOPIC1 = "topic-insbot";

    //平台HTTP报警配置(与机器人一致,通过HTTP上报报警到平台)
    public static final String PLATFORM_BASE_URL = ACTIVE_SITE.getPlatformBaseUrl();//当前现场平台地址
    public static final String ALARM_ROBOT_ID = ACTIVE_SITE.getAlarmRobotId();//当前现场报警机器人ID

    private static List<Integer> listOf(int[] floors) {
        List<Integer> list = new ArrayList<>(floors.length);
        for (int f : floors) list.add(f);
        return list;
    }

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
