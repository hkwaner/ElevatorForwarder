package org.example.demo2.bean;

public class UsedRobotInfo {
    private String usedRobotId;
    private String usedRobotName;
    private int robotUsedStatus;

    public UsedRobotInfo(String usedRobotId, String usedRobotName, int robotUsedStatus) {
        this.usedRobotId = usedRobotId;
        this.usedRobotName = usedRobotName;
        this.robotUsedStatus = robotUsedStatus;
    }

    public String getUsedRobotId() {
        return usedRobotId;
    }

    public String getUsedRobotName() {
        return usedRobotName;
    }

    public int getRobotUsedStatus() {
        return robotUsedStatus;
    }

    public void setUsedRobotId(String usedRobotId) {
        this.usedRobotId = usedRobotId;
    }

    public void setUsedRobotName(String usedRobotName) {
        this.usedRobotName = usedRobotName;
    }

    public void setRobotUsedStatus(int robotUsedStatus) {
        this.robotUsedStatus = robotUsedStatus;
    }
}
