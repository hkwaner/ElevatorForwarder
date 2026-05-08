package org.example.demo2.bean;

/**
 * 占用电梯的用户信息
 */
public class OccupyUserInfo {
    private String userId;//谁在占用电梯 用户id 或 机器人id
    private String userName;//谁在占用电梯 用户名称 或 机器人呢明明成
    private long occupyTime;//开始占用的时间  System.nanoTime()

    public OccupyUserInfo(String userId, String userName, long occupyTime) {
        this.userId = userId;
        this.userName = userName;
        this.occupyTime = occupyTime;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserName() {
        return userName;
    }

    public long getOccupyTime() {
        return occupyTime;
    }

}
