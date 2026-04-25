# 电梯与机器人消息的转发服务
电梯消息中 data2带有80就代表独占成功手控面板此时不生效

## 手动模式下控制机器人逻辑
为了快速看到机器人巡检效果 暂定手动控制巡检逻辑<br/>
手动控制前进后退时 以每层地图内 电梯外的第一个标签为界限(候梯点)  到达或超越界限后 只能往远离电梯的方向控制  不能往靠近电梯的位置控制<br/>
机器人会在广播base_info中增加一个状态 表达和电梯任务相关的状态 如 robot_status_elevator<br/>
巡检路线示例按照 1层 任务1,任务2,任务3,任务4 2层 任务1,任务2,任务3,任务4,3层 任务1,任务2 任务3,任务4  完 回充<br/>


1. 原来有(可能需要再加些逻辑?)        平台控制切换到手动模式 (机器人会停止移动停在原地)
2. 已完成                          平台发送占用电梯(发送mqtt) 如果是其他机器人在占用电梯 需要等待其机器人使用完后释放电梯 如果是当前机器人在使用电梯 需要先控制当前机器人去候梯点释放独占.之后平台再尝试占用电梯
3. 未完成                          如果电梯和机器人不在同一层 平台手动控电梯到达机器人所在楼层 (发送mqtt)
4. 未完成                          电梯到达同层后平台手动通知机器人进电梯(发送mqtt)  机器人会找电梯到位并更新 robot_status_elevator = 1 表示为正在进电梯
5. 未完成                          机器人到达电梯内到位后 会停下并更新 robot_status_elevator = 2 表示为 机器人在电梯内  
6. 平台已完成,机器人未完成            平台控制电梯去某一层(发送mqtt)  等待电梯到达目标楼层
7. 平台已完成,机器人未完成            平台通知机器人出电梯(发送mqtt)  机器人会出电梯并更新 robot_status_elevator =3 表示为正在出电梯
8. 未完成                          机器人到达界限标签后 更新 robot_status_elevator=0 无状态   如果之前是巡检状态会继续巡检流程  回充状态会继续执行回充流程 其他状态会停在候梯点
9. 已完成                          平台发送取消占用电梯(发送mqtt)

robot_status_elevator不为0时平台机器人行为状态要更新对文字显示



### 平台控制相关功能
## mqtt的topic使用单独的为topic-elevator
有个单独的电梯代理服务,负责中转和处理其他程序与电梯之前的信息  所有需要使用电梯的程序都通过这个服务使用电梯


#### 电梯状态信息广播
电梯代理服务会通过mqtt的方式定时广播电梯状态

```json
{
  "id": 602953260,
  "timeStamp": 1776656531505,
  "source": "elevator_proxy",
  "target": "subscribers",
  "type": "ELEVATOR_BROADCAST_INFO",
  "action": "ELEVATOR_BASE_INFO",
  "value": "{\"originalData\":[-96,1,0,0,10,-45],\"receiveTime\":1776656531422,\"isLeveling\":false,\"floor\":0,\"isMovingUp\":true,\"isMovingDown\":false,\"isMoving\":true,\"isElevatorNormal\":false,\"isOccupiedError\":false,\"occupiedUser\":null,\"occupiedUserName\":null}",
  "originalType": null,
  "originalAction": null
}
```

其中value是电梯消息
```json5
{
    "originalData":[0xA0, 0x05, 0x00, 0x00, 0x0A, 0x8B],//电梯返回的原始数据
    //下面是根据原始数据解析道的状态。前端只使用状态即可
    "receiveTime": 1776484742897,//消息接收时间
    "isLeveling": true,//电梯是否在平层
    "floor": 5,//电梯当前楼层 运行中会为0?
    "isMovingUp": false,//电梯是否正在上行中
    "isMovingDown": false,//电梯是否正下行中
    "isMoving": false,//电梯是否正在移动中
    "status": "正常",//正常和异常状态(TODO其他异常对应的String值)
    "occupiedUser": "429370_Y_1000000_1767771361861_224314",//独占成功时的mqtt的source null表示没人独占
    "occupiedUserName": "张三",//独占时用户的名称或机器人名称null表示没人独占
}
```


通用返回实例
```json5
{
  "type": "RESULT",
  "action": "RESULT_SUCCESS",//或失败时为 RESULT_FAIL
  "originalType":"ELEVATOR_CONTROL",
  "originalAction": "OCCUPY_ELEVATOR",//或ENTER_ELEVATOR或SELECT_FLOORS之类的原始cation
  "value":"提示信息"
}
```

##### 功能1.平台占用电梯
使用电梯前需要先占用电梯之后才能控制电梯 发送mqtt返回成功后可以控制电梯
```json5
{
  "type": "ELEVATOR_CONTROL",
  "action": "OCCUPY_ELEVATOR",
  "target": "elevator_proxy",
  "source": "429370_Y_1000000_1767771361861_224314",
  "value": "{\"userId\":\"123456\",\"userName\":\"张三\"}",//操作人id,名称
}
```
返回参考通用示例


##### 功能2.平台通知机器人进去电梯
发送mqtt 通知机器人执行进入电梯 并到位 (平台通知电梯代理服务 然后再由电梯代理服务通知机器人 中间电梯代理会检测电梯状态)
```json5
{
  "type": "ELEVATOR_CONTROL",
  "action": "ENTER_ELEVATOR",
  "target": "elevator_proxy",
  "source": "429370_Y_1000000_1767771361861_224314", 
  "value": "{\"userId\":\"123456\",\"userName\":\"张三\",\"robotId\":\"FCICA-FB260018\"}",//操作人id,名称,要通知的机器人id
}
```
返回参考通用示例

##### 功能3.平台发送选层
发送mqtt 通知电梯去某一层
如果没有占用电梯会返回失败并提示先占用
```json5

{
  "type": "ELEVATOR_CONTROL",
  "action": "SELECT_FLOORS",
  "target": "elevator_proxy",
  "source": "429370_Y_1000000_1767771361861_224314",
  "value": "{\"userId\":\"123456\",\"userName\":\"张三\",\"targetFloor\":1}",//操作人id,名称,目标楼层
}
```
返回参考通用示例

##### 功能4.平台通知机器人出去电梯 
发送mqtt 通知机器人执行出电梯 并到候梯点 (平台通知电梯代理服务 然后再由电梯代理服务通知机器人 中间电梯代理会检测电梯状态)

```json5
{
  "type": "ELEVATOR_CONTROL",
  "action": "EXIT_ELEVATOR",
  "target": "elevator_proxy",
  "source": "429370_Y_1000000_1767771361861_224314",
  "value": "{\"userId\":\"123456\",\"userName\":\"张三\",\"robotId\":\"FCICA-FB260018\"}",//操作人id,名称,要通知的机器人id
}
```
返回参考通用示例

##### 功能5.平台通知机器人去候梯点
发送mqtt 通知机器人去候梯点 与出电梯不同 出电梯只有机器人在电梯内才会执行否则会返回提示 去候梯点 无论机器人在那个位置都可以去候梯点
```json5
{
  "type": "ELEVATOR_CONTROL",
  "action": "TO_WAITING_POINT",
  "target": "elevator_proxy",
  "source": "429370_Y_1000000_1767771361861_224314",
  "value": "{\"userId\":\"123456\",\"userName\":\"张三\",\"robotId\":\"FCICA-FB260018\"}",//操作人id,名称,要通知的机器人id
}
```
返回参考通用示例

##### 功能6.平台取消占用电梯  
发送mqtt 取消使用电梯
```json5
{
  "type": "ELEVATOR_CONTROL",
  "action": "RELEASE_ELEVATOR",
  "target": "elevator_proxy",
  "value": "{\"userId\":\"123456\",\"userName\":\"张三\"}",//操作人id,名称
}
```
返回参考通用示例
