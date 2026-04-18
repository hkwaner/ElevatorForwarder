package org.example.demo2.mqtt;

import java.io.Serializable;
import java.util.Random;

public class MqttMsg implements Serializable {
    private static Random random = new Random(System.currentTimeMillis());
    /**
     * 拿机器人广播消息举例:
     * {
     *   "action": "BASE_INFO",
     *   "id": 140106232,
     *   "result": false,
     *   "source": "FLADY-FB120005",
     *   "success": false,
     *   "target": "subscribers",
     *   "timeStamp": 1769746582910,
     *   "type": "BROADCAST_INFO",
     *   "value": "{\"standbyValue\":\"false\",\"isSlip\":\"false\",\"distance\":\"0.606\",\"isStop\":\"true\",\"task_node_name\":\"GT-1\",\"isReadRFIDInMap\":\"false\",\"task_node_id\":\"11\",\"ir_back_temperatures\":\"[25.159]\",\"task_node_start_location\":\"1.0\",\"speed\":\"0.0\",\"isHinder\":\"false\",\"mode\":\"-1\",\"ir_front_temperatures\":\"[]\",\"rfid_tag\":\"E0040108406F4C6300000000\",\"needCharge\":\"false\",\"robot_direction\":\"1\"}"
     * }
     */

    /**
     * 消息id，用于去重，暂用随机数
     */
    private long id = 0;
    private long timeStamp;
    private String source;
    private String target;

    //类型KEY
    private String type;
    //行为VALUE
    private String action;

    private Object value;

    /**
     * 记录原有消息类型，便于判断返回结果对应那条指令
     */
    private String originalType;

    /**
     * 记录原有消息类型，便于判断返回结果对应那条指令
     */
    private String originalAction;

    {
        timeStamp = System.currentTimeMillis();
        id = random.nextInt(Integer.MAX_VALUE);
    }


    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getOriginalType() {
        return originalType;
    }

    public void setOriginalType(String originalType) {
        this.originalType = originalType;
    }

    public String getOriginalAction() {
        return originalAction;
    }

    public void setOriginalAction(String originalAction) {
        this.originalAction = originalAction;
    }
}
