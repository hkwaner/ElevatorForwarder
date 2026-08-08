package org.example.demo2.elevator;

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
 */
public class ElevatorResultHandler extends Thread {
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

    private ElevatorResultHandler() {
        setName("ElevatorResultHandler");
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

    @Override
    public void run() {
        ElevatorConnector connector = ElevatorConnector.getInstance();
        ElevatorResult pending = null; // 冷却期间暂存帧,不丢弃
        while (runFlag) {
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
                if (newer != null) pending = newer;
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

            lastBroadcastMs = System.currentTimeMillis();
            mqttManager.broadcastElevatorResult(result);
        }
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

    public void stopRun() {
        runFlag = false;
        this.interrupt();
    }
}
