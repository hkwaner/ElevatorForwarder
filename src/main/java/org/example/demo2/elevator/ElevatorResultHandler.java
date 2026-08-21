package org.example.demo2.elevator;

import org.example.demo2.Config;
import org.example.demo2.alarm.ElevatorAlarmReporter;
import org.example.demo2.alarm.ElevatorAlarmType;
import org.example.demo2.mqtt.MqttManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 处理接收到的电梯消息,处理后通过 mqtt 广播出去。
 * <p>
 * 事件驱动 + 单槽最新值:电梯侧每收到一帧调用 {@link #submit(ElevatorResult)} 放入容量1的槽,
 * 本线程从槽取最新帧,按 1 秒墙钟冷却后广播。槽只保留最新一帧(新覆盖旧),不会积压陈旧消息。
 * <p>
 * 支持看门狗重启:内部工作线程,提供心跳和重启方法。
 */
public class ElevatorResultHandler {
    private static final Logger log = LoggerFactory.getLogger(ElevatorResultHandler.class);
    private volatile boolean runFlag = true;
    private final MqttManager mqttManager = MqttManager.getInstance();

    /** 单槽:容量1,只保留最新一帧,新帧覆盖旧帧,防止积压 */
    private final BlockingQueue<ElevatorResult> slot = new ArrayBlockingQueue<>(1);
    /** 最新已接收帧(不论是否已广播),供占用确认等读取最新状态 */
    private volatile ElevatorResult latestReceived;
    /** 上次广播墙钟时间(ms),用于 1 秒冷却 */
    private volatile long lastBroadcastMs = 0;
    /** 广播最小间隔(毫秒) */
    private static final long BROADCAST_INTERVAL_MS = 900L;

    /** 看门狗心跳:每次循环更新 */
    private volatile long lastLoopTimeMs = System.currentTimeMillis();
    /** 内部工作线程 */
    private Thread worker;

    // ==================== 卡住检测与跨楼层重试 ====================
    /** 卡住判定阈值:楼层未变化持续超过此时间(毫秒)视为卡住 */
    private static final long STUCK_THRESHOLD_MS = 120_000L;
    /** 重试超时:每个重试阶段的最大等待时间(毫秒) */
    private static final long RETRY_TIMEOUT_MS = 120_000L;
    /** 最大跨楼层重试次数(超过后直接发目标楼层或放弃) */
    private static final int MAX_RETRY_ATTEMPTS = 3;
    /** 上次原始楼层,用于检测楼层变化 */
    private volatile int lastRawFloor = -1;
    /** 上次楼层变化时间(ms) */
    private volatile long lastFloorChangeMs = System.currentTimeMillis();
    /** 上一帧是否运动中,用于电梯从静止启动时重置卡住计时 */
    private volatile boolean lastMoving = false;
    /** 重试状态 */
    private enum RetryState { NORMAL, RETRY_MOVING, RETRY_RETURNING }
    private volatile RetryState retryState = RetryState.NORMAL;
    /** 重试中转楼层 */
    private volatile int retryFloorValue = 0;
    /** 重试的原始目标楼层 */
    private volatile int retryTargetFloor = 0;
    /** 当前重试阶段开始时间(ms) */
    private volatile long retryStartTime = 0;
    /** 当前重试次数(初始重试为1,每次失败递增) */
    private volatile int retryAttemptCount = 0;
    /** 上次尝试过的中转楼层(重试时排除,避免重复选同一层) */
    private volatile int lastTriedRetryFloor = 0;

    private ElevatorResultHandler() {
    }

    private static final class InstanceHolder {
        private static final ElevatorResultHandler INSTANCE = new ElevatorResultHandler();
    }

    public static ElevatorResultHandler getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * 电梯侧每收到一帧调用:放入单槽,由广播线程取最新帧发送。
     * worker 正在忙时,新帧会覆盖槽中尚未消费的旧帧,不会积压。
     */
    public void submit(ElevatorResult result) {
        if (result == null) return;
        latestReceived = result;
        if (!slot.offer(result)) {
            slot.clear();
            slot.offer(result);
        }
    }

    /**
     * 启动内部工作线程
     */
    public synchronized void start() {
        if (worker != null && worker.isAlive()) return;
        runFlag = true;
        worker = new Thread(this::runLoop, "ElevatorResultHandler");
        worker.setDaemon(true);
        worker.start();
    }

    private void runLoop() {
        ElevatorConnector connector = ElevatorConnector.getInstance();
        ElevatorResult pending = null; // 冷却期间暂存帧,不丢弃
        while (runFlag) {
            lastLoopTimeMs = System.currentTimeMillis();

            // 检测数据接收超时:长时间没收到电梯数据,主动关闭连接触发重连
            if (connector.isDataReceiveTimeout()) {
                connector.closeAndReconnect();
            }

            // 用冷却剩余时间作为poll超时:冷却中等到边界,冷却后用200ms做超时检测
            long remaining = BROADCAST_INTERVAL_MS - (System.currentTimeMillis() - lastBroadcastMs);
            long pollTimeout = remaining > 0 ? remaining : 200L;

            // 阻塞等待新帧;有新帧就更新pending为最新
            try {
                ElevatorResult newer = slot.poll(pollTimeout, TimeUnit.MILLISECONDS);
                if (newer != null) {
                    pending = newer;
                    handleStuckDetection(newer);
                }
            } catch (InterruptedException e) {
                continue;
            }

            // 冷却未过:保留pending,继续等
            if (System.currentTimeMillis() - lastBroadcastMs < BROADCAST_INTERVAL_MS) {
                continue;
            }

            // 冷却已过:广播pending(如果有)
            if (pending == null) continue;
            ElevatorResult result = pending;
            pending = null;

            // 重试期间:广播运动状态覆盖,防止机器人在中转楼层平层时误进出
            // 重试成功帧到达时 retryState 已被 handleRetry 重置为 NORMAL,不会覆盖,机器人能正常看到到达
            if (retryState != RetryState.NORMAL) {
                result = result.asMovingOverride();
            }

            lastBroadcastMs = System.currentTimeMillis();
            mqttManager.broadcastElevatorResult(result);
        }
    }

    // ==================== 卡住检测与跨楼层重试 ====================

    /**
     * 检测电梯卡住并自动跨楼层重试。
     * 电梯运动中且不在平层时,如果楼层长时间未变化,发送跨楼层指令打破卡死,
     * 到达中转楼层后再重新发送原始目标楼层。
     */
    private void handleStuckDetection(ElevatorResult result) {
        int rawFloor = result.getRawFloor();
        int target = ElevatorConnector.getInstance().getTargetFloor();

        // 跟踪楼层变化
        if (rawFloor != lastRawFloor) {
            lastRawFloor = rawFloor;
            lastFloorChangeMs = System.currentTimeMillis();
        }

        // 电梯从静止转为运动时,重置卡住计时
        // (避免电梯长时间停在某层后启动时,因 lastFloorChangeMs 过期而误判卡住)
        if (!lastMoving && result.isMoving()) {
            lastFloorChangeMs = System.currentTimeMillis();
        }
        lastMoving = result.isMoving();

        // 重试流程中:交给重试状态机处理
        if (retryState != RetryState.NORMAL) {
            handleRetry(result, target);
            return;
        }

        // 目标到达:清零目标
        if (target > 0 && result.isLeveling() && result.getFloor() == target) {
            ElevatorConnector.getInstance().clearTargetFloor();
            return;
        }

        // 只在运动中 + 不在平层 + 有目标楼层时检测
        if (target <= 0 || !result.isMoving() || result.isLeveling()) {
            return;
        }

        // 检测卡住:楼层长时间未变
        long stuckDuration = System.currentTimeMillis() - lastFloorChangeMs;
        if (stuckDuration > STUCK_THRESHOLD_MS) {
            int retryFloor = pickRetryFloor(rawFloor, target);
            log.warn("[电梯卡住检测] 楼层{}已{}秒未变化,运动中未平层,跨楼层重试:卡住楼层{}→中转{}→目标{}",
                    rawFloor, stuckDuration / 1000, rawFloor, retryFloor, target);
            retryTargetFloor = target;
            retryFloorValue = retryFloor;
            retryStartTime = System.currentTimeMillis();
            lastFloorChangeMs = System.currentTimeMillis();
            retryAttemptCount = 1;
            lastTriedRetryFloor = 0;
            retryState = RetryState.RETRY_MOVING;
            // 锁定重试:拦截机器人对原始目标楼层的自动重发,防止覆盖中转楼层指令
            ElevatorConnector.getInstance().setRetryLocked(true, target);
            ElevatorConnector.getInstance().setSelectFloorForRetry(retryFloor);
            // 电梯卡住报警(自动重试前,HTTP上报平台):完整自定义内容
            ElevatorAlarmReporter.report(ElevatorAlarmType.STUCK,
                    "检测到电梯卡在" + rawFloor + "层,目标" + target + "层,正在自动处理");
        }
    }

    /**
     * 重试状态机:处理中转楼层到达、目标楼层返回、以及重试过程中的再次卡住。
     * <p>
     * 重试期间广播保持电梯原始状态(运动中),不注入异常状态:
     * 机器人端收到异常状态会触发软件急停,运动状态下只是继续等待到达。
     */
    private void handleRetry(ElevatorResult result, int target) {
        // 外部新选层指令(机器人目标变了):即使楼层恰好等于中转楼层也能检测到,立即放弃重试
        if (ElevatorConnector.getInstance().consumeExternalFloorOverride()) {
            log.info("[电梯卡住恢复] 外部新选层指令(target={}),放弃重试", target);
            resetRetryState();
            return;
        }

        // 用户手动发了新的选层指令:放弃重试
        if ((retryState == RetryState.RETRY_MOVING && target != retryFloorValue)
                || (retryState == RetryState.RETRY_RETURNING && target != retryTargetFloor)) {
            log.info("[电梯卡住恢复] 检测到新选层指令(target={}),放弃重试", target);
            resetRetryState();
            return;
        }

        long now = System.currentTimeMillis();
        int rawFloor = result.getRawFloor();
        boolean stuckAgain = (now - lastFloorChangeMs) > STUCK_THRESHOLD_MS;
        boolean timeout = (now - retryStartTime) > RETRY_TIMEOUT_MS;

        switch (retryState) {
            case RETRY_MOVING:
                if (result.isLeveling() && result.getFloor() == retryFloorValue) {
                    log.info("[电梯卡住恢复] 到达中转楼层{},重新发送目标楼层{}", retryFloorValue, retryTargetFloor);
                    retryStartTime = now;
                    lastFloorChangeMs = now;
                    retryState = RetryState.RETRY_RETURNING;
                    ElevatorConnector.getInstance().setSelectFloorForRetry(retryTargetFloor);
                } else if (stuckAgain || timeout) {
                    retryAttemptCount++;
                    if (retryAttemptCount > MAX_RETRY_ATTEMPTS) {
                        // 超过最大重试次数:直接发送目标楼层做最后尝试
                        log.warn("[电梯卡住恢复] 中转楼层重试{}次仍卡住,直接发送目标楼层{}做最后尝试",
                                MAX_RETRY_ATTEMPTS, retryTargetFloor);
                        retryStartTime = now;
                        lastFloorChangeMs = now;
                        retryState = RetryState.RETRY_RETURNING;
                        ElevatorConnector.getInstance().setSelectFloorForRetry(retryTargetFloor);
                    } else {
                        // 换一个中转楼层重试(排除上次失败的)
                        int newRetryFloor = pickRetryFloor(rawFloor, retryTargetFloor, lastTriedRetryFloor);
                        log.warn("[电梯卡住恢复] 中转{}{},第{}/{}次重试:楼层{}→新中转{}→目标{}",
                                retryFloorValue, stuckAgain ? "再次卡住" : "超时",
                                retryAttemptCount, MAX_RETRY_ATTEMPTS, rawFloor, newRetryFloor, retryTargetFloor);
                        lastTriedRetryFloor = retryFloorValue;
                        retryFloorValue = newRetryFloor;
                        retryStartTime = now;
                        lastFloorChangeMs = now;
                        ElevatorConnector.getInstance().setSelectFloorForRetry(newRetryFloor);
                    }
                }
                break;

            case RETRY_RETURNING:
                if (result.isLeveling() && result.getFloor() == retryTargetFloor) {
                    log.info("[电梯卡住恢复] 到达目标楼层{},重试成功", retryTargetFloor);
                    ElevatorConnector.getInstance().clearTargetFloor();
                    // 电梯卡住恢复报警(重试成功,HTTP上报平台):完整自定义内容
                    ElevatorAlarmReporter.report(ElevatorAlarmType.RECOVERED,
                            "检测到电梯已恢复,到达目标" + retryTargetFloor + "层");
                    resetRetryState();
                } else if (stuckAgain || timeout) {
                    retryAttemptCount++;
                    if (retryAttemptCount > MAX_RETRY_ATTEMPTS) {
                        log.warn("[电梯卡住恢复] 目标楼层{}重试{}次仍卡住,放弃重试,记录日志不再上报报警",
                                retryTargetFloor, MAX_RETRY_ATTEMPTS);
                        resetRetryState();
                    } else {
                        // 返回目标楼层又卡住:重新跨楼层
                        int newRetryFloor = pickRetryFloor(rawFloor, retryTargetFloor, lastTriedRetryFloor);
                        log.warn("[电梯卡住恢复] 返回目标{}{},第{}/{}次跨楼层:楼层{}→新中转{}→目标{}",
                                retryTargetFloor, stuckAgain ? "再次卡住" : "超时",
                                retryAttemptCount, MAX_RETRY_ATTEMPTS, rawFloor, newRetryFloor, retryTargetFloor);
                        lastTriedRetryFloor = retryFloorValue;
                        retryFloorValue = newRetryFloor;
                        retryStartTime = now;
                        lastFloorChangeMs = now;
                        retryState = RetryState.RETRY_MOVING;
                        ElevatorConnector.getInstance().setSelectFloorForRetry(newRetryFloor);
                    }
                }
                break;
        }
    }

    private void resetRetryState() {
        retryState = RetryState.NORMAL;
        retryFloorValue = 0;
        retryTargetFloor = 0;
        retryStartTime = 0;
        retryAttemptCount = 0;
        lastTriedRetryFloor = 0;
        // 解除重试锁,恢复机器人的选层指令通道
        ElevatorConnector.getInstance().setRetryLocked(false, 0);
    }

    /**
     * 选择中转楼层:根据卡住时电梯返回的楼层,选离卡住楼层最远的可用楼层(确保跨楼层)
     *
     * @param stuckFloor   电梯卡住时返回的楼层(实际卡住位置)
     * @param targetFloor  原始目标楼层(需排除,避免选中目标本身)
     */
    private int pickRetryFloor(int stuckFloor, int targetFloor) {
        return pickRetryFloor(stuckFloor, targetFloor, 0);
    }

    /**
     * 选择中转楼层(带排除):排除上次失败的中转楼层,避免重复选择
     *
     * @param stuckFloor    电梯卡住时返回的楼层(实际卡住位置)
     * @param targetFloor   原始目标楼层(需排除)
     * @param excludeFloor  上次失败的中转楼层(0表示不排除)
     */
    private int pickRetryFloor(int stuckFloor, int targetFloor, int excludeFloor) {
        int retryFloor = stuckFloor;
        int maxDist = 0;
        for (int f : Config.ELEVATOR_FLOORS) {
            if (f != stuckFloor && f != targetFloor && f != excludeFloor) {
                int dist = Math.abs(f - stuckFloor);
                if (dist > maxDist) {
                    maxDist = dist;
                    retryFloor = f;
                }
            }
        }
        return retryFloor;
    }

    /**
     * 最近一次接收到的电梯状态(只读)
     */
    public ElevatorResult getLastResult() {
        return latestReceived;
    }

    /**
     * 检查占用电梯操作是否完成(基于最新接收帧,而非上次广播帧)
     */
    public boolean checkOccupiedSuccess(long userOccupyTime) {
        ElevatorResult latest = latestReceived;
        if (latest == null) return false;
        boolean timeFlag = (latest.getReceiveTimeNano() - userOccupyTime >= TimeUnit.MILLISECONDS.toNanos(500));
        return timeFlag && latest.isOccupiedSuccess();
    }

    public long getLastLoopTimeMs() {
        return lastLoopTimeMs;
    }

    /**
     * 看门狗触发:停止旧线程并启动新线程
     */
    public synchronized void restart() {
        log.info("[ElevatorResultHandler] 看门狗触发，重启线程");
        runFlag = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        start();
    }

    public void stopRun() {
        runFlag = false;
        if (worker != null) {
            worker.interrupt();
        }
    }
}