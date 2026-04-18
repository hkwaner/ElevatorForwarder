package org.example.demo2.elevator;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.example.demo2.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 电梯连接器 - 通过 Netty TCP 与电梯保持连接
 */
public class ElevatorConnectorBackUp {
    private static final Logger log = LoggerFactory.getLogger(ElevatorConnectorBackUp.class);

    private EventLoopGroup workerGroup;
    private Channel channel;
    private volatile boolean runFlag = true;

    // 重连配置
    private int reconnectDelay = 5;
    private final int maxReconnectDelay = 20;

    //最新的电梯返回的状态消息
    private volatile ElevatorResult lastElevatorResult;


    private ElevatorConnectorBackUp() {

    }

    private static final class InstanceHolder {
        private static final ElevatorConnectorBackUp INSTANCE = new ElevatorConnectorBackUp();
    }

    public static ElevatorConnectorBackUp getInstance() {
        return InstanceHolder.INSTANCE;
    }

    public void start() {
        ElevatorResultHandler resultHandler = ElevatorResultHandler.getInstance();
        resultHandler.start();
        workerGroup = new NioEventLoopGroup();
        doConnect();
    }

    private void doConnect() {
        if (!runFlag) return;

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                    @Override
                    protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new LoggingHandler(LogLevel.INFO))
                                .addLast(new ElevatorMessageHandler());
                    }
                });

        log.info("[电梯] 正在连接：{}:{}", Config.ELEVATOR_HOST, Config.ELEVATOR_PORT);

        bootstrap.connect(Config.ELEVATOR_HOST, Config.ELEVATOR_PORT).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                channel = future.channel();
                log.info("[电梯] 连接成功");
                reconnectDelay = 5;
            } else {
                log.info("[电梯] 连接失败：{}", future.cause().getMessage());
                reconnect();
            }
        });
    }

    private void reconnect() {
        if (!runFlag) return;
        log.info("[电梯] {}秒后重连...", reconnectDelay);
        workerGroup.schedule(this::doConnect, reconnectDelay, TimeUnit.SECONDS);
        reconnectDelay = Math.min(reconnectDelay * 2, maxReconnectDelay);
    }

    public void stop() {
        log.info("[电梯] stop>");
        runFlag = false;
        ElevatorResultHandler.getInstance().stopRun();
        if (channel != null) channel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        log.info("[电梯] stop<");
    }

    private boolean isConnected() {
        return channel != null && channel.isActive();
    }

    public boolean setOccupyElevatorUser(boolean occupy) {
        if (!isConnected()) return false;
        ElevatorCommand command = ElevatorCommand.buildToElevatorMsg((byte) 0x00, occupy ? (byte) 0x11 : (byte) 0x01, (byte) 0x00);
        channel.writeAndFlush(command);
        return true;
    }

    public boolean setSelectFloor(int floor) {
        if (!isConnected()) return false;
        byte floorByte = (byte) (floor & 0xFF);
        ElevatorCommand command = ElevatorCommand.buildToElevatorMsg(floorByte, (byte) 0x11, (byte) 0x00);
        channel.writeAndFlush(command);
        return true;
    }

    public ElevatorResult getLastElevatorResult() {
        return lastElevatorResult;
    }


    /**
     * 电梯消息处理器
     */
    private class ElevatorMessageHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private static final int FRAME_LENGTH = 6;// 协议固定长度
        private static final int MAX_FAIL_COUNT = 10;// 最大连续失败次数，防止死循环消耗 CPU
        private int failCount = 0;// 用于记录连续失败的次数

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            while (in.readableBytes() >= FRAME_LENGTH) {// 只要缓冲区里的数据足够 6 个字节，就一直尝试解析
                in.markReaderIndex();// 1. 标记当前位置，万一解析失败，我们要退回到这里

                byte[] data = new byte[FRAME_LENGTH];
                in.readBytes(data);// 2. 尝试读取 6 个字节

                ElevatorResult result = ElevatorResult.convertMsg(data);// 3. 调用方法解析 返回 null 代表 CRC 校验失败
                if (result != null) {
                    //解析成功 重置失败计数器
                    failCount = 0;

                    // 指针保持在当前位置（6个字节已被正式消耗）
                    // 在这里处理你的业务逻辑，比如更新状态
                    lastElevatorResult = result;

                } else {
                    //解析失败 (CRC 错误或数据错位) ---
                    in.resetReaderIndex();// 回滚指针，回到读取这 6 个字节之前的位置
                    failCount++;// 失败计数 +1
                    if (failCount > MAX_FAIL_COUNT) {// 连续失败超过 10 次，说明数据流里全是垃圾，或者协议完全对不上
                        log.info("连续 {} 次 CRC 校验失败，判定数据流严重损坏，强制清空缓冲区！", MAX_FAIL_COUNT);
                        in.clear(); // 暴力清空整个缓冲区
                        failCount = 0; // 重置计数器
                        break; // 退出循环，等待下一次网络数据到来
                    }

                    // 如果没超过 10 次，丢弃 1 个字节，尝试与后面的数据重新对齐
                    byte discardedByte = in.readByte();
                    log.info("CRC校验失败，丢弃 1 字节(0x{}) 尝试重对齐... 当前失败次数: {}", String.format("%02X", discardedByte), failCount);
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            log.info("[电梯] 连接断开");
            reconnect();
            super.channelInactive(ctx);
        }
    }
}
