package org.example.demo2.elevator;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.example.demo2.Config;
import org.example.demo2.LogicHandler;
import org.example.demo2.bean.OccupyUserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 电梯连接器 - 作为 TCP 服务端，等待 5G CPE 连接，通过该连接与电梯通信
 */
public class ElevatorConnector {
    private static final Logger log = LoggerFactory.getLogger(ElevatorConnector.class);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;           // 服务端监听 channel
    private volatile Channel clientChannel;   // 5G CPE 连接上来的 channel

    private volatile boolean runFlag = true;

    // 端口绑定失败重试配置
    private int bindRetryDelay = 5;
    private final int maxBindRetryDelay = 20;

    //最新的电梯返回的状态消息
    private volatile ElevatorResult lastElevatorResult;

    // 最后一次收到电梯数据的时间（毫秒）
    private volatile long lastReceiveTimeMs = System.currentTimeMillis();
    // 数据接收超时阈值（秒），超过此时间没收到数据则认为连接异常
    private static final int DATA_RECEIVE_TIMEOUT_SECONDS = 10;

    // 超时告警节流：上次打印告警的时间（毫秒），避免每100ms轮询都刷屏
    private volatile long lastTimeoutWarnTimeMs = 0;
    // 告警间隔（秒）
    private static final int TIMEOUT_WARN_INTERVAL_SECONDS = 20;

    private ElevatorConnector() {

    }

    private static final class InstanceHolder {
        private static final ElevatorConnector INSTANCE = new ElevatorConnector();
    }

    public static ElevatorConnector getInstance() {
        return InstanceHolder.INSTANCE;
    }

    public void start() {
        ElevatorResultHandler resultHandler = ElevatorResultHandler.getInstance();
        resultHandler.start();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        doBind();
    }

    private void doBind() {
        if (!runFlag) return;

        ServerBootstrap bootstrap = new ServerBootstrap();

        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new OccupyHandler())
                                .addLast(new ElevatorMessageHandler());
                    }
                });

        log.info("[电梯] 正在监听端口：{}", Config.ELEVATOR_PORT);

        bootstrap.bind(Config.ELEVATOR_PORT).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                serverChannel = future.channel();
                bindRetryDelay = 5;
                log.info("[电梯] 端口监听成功，等待5G CPE连接...");
            } else {
                log.info("[电梯] 端口监听失败：{}", future.cause().getMessage());
                retryBind();
            }
        });
    }

    private void retryBind() {
        if (!runFlag) return;
        log.info("[电梯] {}秒后重试监听端口...", bindRetryDelay);
        workerGroup.schedule(this::doBind, bindRetryDelay, TimeUnit.SECONDS);
        bindRetryDelay = Math.min(bindRetryDelay * 2, maxBindRetryDelay);
    }

    public void stop() {
        log.info("[电梯] stop>");
        runFlag = false;
        ElevatorResultHandler.getInstance().stopRun();
        if (clientChannel != null) clientChannel.close();
        if (serverChannel != null) serverChannel.close();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        log.info("[电梯] stop<");
    }

    private boolean isConnected() {
        return clientChannel != null && clientChannel.isActive();
    }

    public boolean setOccupyElevatorUser(boolean occupy) {
        if (!isConnected()) return false;
        ElevatorCommand command = ElevatorCommand.buildToElevatorMsg((byte) 0x00, occupy ? (byte) 0x12 : (byte) 0x02, (byte) 0x00);
        log.info("setOccupyElevatorUser 发送指令 command:{}", command);
        ByteBuf buffer = Unpooled.wrappedBuffer(command.getBytes());//将 byte[] 包装成 ByteBuf (不复制内存，直接使用原数组)
        clientChannel.writeAndFlush(buffer);
        return true;
    }

    public boolean setSelectFloor(int floor) {
        if (!isConnected()) return false;
        byte floorByte = (byte) (floor & 0xFF);
        ElevatorCommand command = ElevatorCommand.buildToElevatorMsg(floorByte, (byte) 0x12, (byte) 0x00);
        log.info("setSelectFloor 发送指令 command:{}", command);
        ByteBuf buffer = Unpooled.wrappedBuffer(command.getBytes());//将 byte[] 包装成 ByteBuf (不复制内存，直接使用原数组)
        clientChannel.writeAndFlush(buffer);
        return true;
    }

    protected ElevatorResult getLastElevatorResult() {
        return lastElevatorResult;
    }

    /**
     * 检查是否长时间未收到电梯数据
     * @return true 表示超时，需要处理
     */
    public boolean isDataReceiveTimeout() {
        if (!isConnected()) return false;  // 没有连接时不判断超时
        long elapsed = System.currentTimeMillis() - lastReceiveTimeMs;
        return elapsed > DATA_RECEIVE_TIMEOUT_SECONDS * 1000L;
    }

    /**
     * 数据接收超时处理。
     * 服务端模式下不主动关闭连接（5G CPE 不一定会自动重连），
     * 只记录告警日志，等待 CPE 恢复数据发送或自行断开重连。
     */
    public void closeAndReconnect() {
        long now = System.currentTimeMillis();
        if (now - lastTimeoutWarnTimeMs > TIMEOUT_WARN_INTERVAL_SECONDS * 1000L) {
            lastTimeoutWarnTimeMs = now;
            log.warn("[电梯] 数据接收超时，服务端模式不主动关闭连接，等待5G CPE恢复");
        }
    }


    /**
     * 电梯消息处理器
     */
    private class ElevatorMessageHandler extends ByteToMessageDecoder {
        private int failCount;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            clientChannel = ctx.channel();
            lastReceiveTimeMs = System.currentTimeMillis();
            log.info("[电梯] 5G CPE已连接: {}", ctx.channel().remoteAddress());
            super.channelActive(ctx);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            // 1. 只要缓冲区里有数据，就一直尝试解析
            while (in.isReadable()) {
                // --- 场景 A: 半包 (留着) ---
                // 如果不够 6 个字节，直接跳出循环。
                // ByteToMessageDecoder 会自动保留这些数据，等下次凑够了再来。
                if (in.readableBytes() < 6) {
                    log.info("长度不够6位等下次");
                    break;
                }

                // --- 尝试读取一个完整的包 ---
                // 标记当前位置，万一校验失败，我们要回退
                in.markReaderIndex();

                byte[] frameBytes = new byte[6];
                in.readBytes(frameBytes);

                // --- 场景 B & C: 校验 ---
                ElevatorResult result = ElevatorResult.convertMsg(frameBytes);

                if (result != null) {
                    // --- 场景 B: 对的 (往下传) ---
                    // 校验成功，把结果放入 out，Netty 会自动传给下一个 Handler
                    //out.add(result);
                    log.info("解析到消息 result:{}", result);
                    lastElevatorResult = result;
                    lastReceiveTimeMs = System.currentTimeMillis();
                    failCount = 0; // 重置错误计数
                    // 继续 while 循环，看看后面是不是还粘着一个包
                } else {
                    // --- 场景 C: 错的 (丢弃) ---
                    // 校验失败！

                    // 1. 回退指针（因为刚才 readBytes(6) 把指针移走了）
                    in.resetReaderIndex();

                    // 2. 丢弃 1 个字节（这是关键！跳过这个错误的帧头）
                    byte discardedByte = in.readByte();
                    failCount++;
                    log.info("CRC校验失败，丢弃 1 字节(0x{}) 尝试重对齐... 当前失败次数: {}", String.format("%02X", discardedByte), failCount);
                    if (failCount > 10) {
                        log.info("连续校验失败，可能流控失控，清空缓冲区");
                        in.clear(); // 防止死循环
                        failCount = 0;
                    }
                    // 继续 while 循环，立刻检查下一个字节是不是正确的帧头
                }
            }
        }


        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            log.info("[电梯] 5G CPE连接断开");
            if (clientChannel == ctx.channel()) {
                clientChannel = null;
            }
            lastElevatorResult = null;
            // 服务端模式：不主动重连，等待5G CPE自行重连
            super.channelInactive(ctx);
        }
    }

    /**
     * 维护 占用用户状态 如果持续5秒钟超时没有写操作 则发送一次当前独占电梯的信息
     */
    private class OccupyHandler extends IdleStateHandler {
        public OccupyHandler() {
            // 直接在构造函数设置时间：5秒无写操作触发。5秒一次
            super(0, 5, 0, TimeUnit.SECONDS);
        }

        @Override
        protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
            if (evt.state() == IdleState.WRITER_IDLE) {
                // 直接在这里写发送逻辑
                if (ctx.channel() == null || !ctx.channel().isActive()) return;
                OccupyUserInfo occupyUserInfo = LogicHandler.getInstance().getOccupyUserInfo();
                if (occupyUserInfo != null) {
                    ElevatorCommand command = ElevatorCommand.buildToElevatorMsg((byte) 0x00, (byte) 0x12, (byte) 0x00);
                    log.info("连续5秒没有写操作,发送独占,保持独占信息 {}",command);
                    ByteBuf buffer = Unpooled.wrappedBuffer(command.getBytes());//将 byte[] 包装成 ByteBuf (不复制内存，直接使用原数组)
                    ctx.writeAndFlush(buffer);
                }
            }
        }
    }
}
