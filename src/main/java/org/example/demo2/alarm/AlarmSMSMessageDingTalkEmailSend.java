package org.example.demo2.alarm;

import java.io.Serializable;
import java.util.List;

/**
 * 平台报警上报请求体,字段与机器人端/平台端 AlarmSMSMessageDingTalkEmailSend 保持一致。
 * 通过 HTTP POST 到平台 /communication/alarm-sms-message-ding-talk-email-send。
 */
public class AlarmSMSMessageDingTalkEmailSend implements Serializable {

    public static final String URL = "/communication/alarm-sms-message-ding-talk-email-send";

    private String robotId;              // 机器人id(平台按此定位项目)
    private String alarmTypeId;          // 报警ID(如 T_00127)
    private boolean isRegain;            // 是否异常恢复
    private List<String> alarmAnalysis;  // 异常占位符数据(对应模板中的 %s)
    private int alarmObj;                // 默认0机器人
    private String picAddress;           // 报警热成像摄像头图片
    private String picWebAddress;        // 报警可见光摄像头图片
    private Integer taskNodeId;          // 节点id
    private Integer taskObjId;           // 对象id

    public String getRobotId() {
        return robotId;
    }

    public void setRobotId(String robotId) {
        this.robotId = robotId;
    }

    public String getAlarmTypeId() {
        return alarmTypeId;
    }

    public void setAlarmTypeId(String alarmTypeId) {
        this.alarmTypeId = alarmTypeId;
    }

    public boolean getIsRegain() {
        return isRegain;
    }

    public void setIsRegain(boolean regain) {
        this.isRegain = regain;
    }

    public List<String> getAlarmAnalysis() {
        return alarmAnalysis;
    }

    public void setAlarmAnalysis(List<String> alarmAnalysis) {
        this.alarmAnalysis = alarmAnalysis;
    }

    public int getAlarmObj() {
        return alarmObj;
    }

    public void setAlarmObj(int alarmObj) {
        this.alarmObj = alarmObj;
    }

    public String getPicAddress() {
        return picAddress;
    }

    public void setPicAddress(String picAddress) {
        this.picAddress = picAddress;
    }

    public String getPicWebAddress() {
        return picWebAddress;
    }

    public void setPicWebAddress(String picWebAddress) {
        this.picWebAddress = picWebAddress;
    }

    public Integer getTaskNodeId() {
        return taskNodeId;
    }

    public void setTaskNodeId(Integer taskNodeId) {
        this.taskNodeId = taskNodeId;
    }

    public Integer getTaskObjId() {
        return taskObjId;
    }

    public void setTaskObjId(Integer taskObjId) {
        this.taskObjId = taskObjId;
    }
}
