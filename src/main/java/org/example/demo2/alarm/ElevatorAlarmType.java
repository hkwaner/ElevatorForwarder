package org.example.demo2.alarm;

/**
 * 电梯报警类型:封装平台报警类型编号与是否恢复标记。
 * 编号为临时值,后续按现场数据库/项目配置修改。
 */
public enum ElevatorAlarmType {
    STUCK("T_00140", false),
    // 恢复报警:平台恢复链路依赖历史记录配对,单独发恢复常取不到记录而失败;
    // 故与卡住一样按普通报警(isRegain=false)上报,内容由代码端自定义完整文案。
    RECOVERED("RT_00140", false);

    private final String typeId;
    private final boolean regain;

    ElevatorAlarmType(String typeId, boolean regain) {
        this.typeId = typeId;
        this.regain = regain;
    }

    public String getTypeId() {
        return typeId;
    }

    public boolean isRegain() {
        return regain;
    }
}
