package org.example.demo2.elevator;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.example.demo2.Config;
import org.example.demo2.LogicHandler;
import org.example.demo2.utils.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 电梯连接器 - 通过 Netty TCP 与电梯保持连接
 */
public class ElevatorConnector {
    private static final Logger log = LoggerFactory.getLogger(ElevatorConnector.class);

    private EventLoopGroup workerGroup;
    private Channel channel;
    private volatile boolean runFlag = true;

    // 重连配置
    private int reconnectDelay = 5;
    private final int maxReconnectDelay = 20;

    //最新的电梯返回的状态消息
    private volatile ElevatorResult lastElevatorResult;


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
        workerGroup = new NioEventLoopGroup();
        doConnect();
    }

    private void doConnect() {
        if (!runFlag) return;

        Bootstrap bootstrap = new Bootstrap();

        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                    @Override
                    protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                        ch.pipeline()
//                                .addLast(new LoggingHandler(LogLevel.INFO))
                                .addLast(new OccupyHandler())
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
        ElevatorCommand command = ElevatorCommand.buildToElevatorMsg((byte) 0x00, occupy ? (byte) 0x12 : (byte) 0x02, (byte) 0x00);
        log.info("setOccupyElevatorUser 发送指令 command:{}", command);
        ByteBuf buffer = Unpooled.wrappedBuffer(command.getBytes());//将 byte[] 包装成 ByteBuf (不复制内存，直接使用原数组)
        channel.writeAndFlush(buffer);
        return true;
    }

    public boolean setSelectFloor(int floor) {
        if (!isConnected()) return false;
        byte floorByte = (byte) (floor & 0xFF);
        ElevatorCommand command = ElevatorCommand.buildToElevatorMsg(floorByte, (byte) 0x12, (byte) 0x00);
        log.info("setSelectFloor 发送指令 command:{}", command);
        ByteBuf buffer = Unpooled.wrappedBuffer(command.getBytes());//将 byte[] 包装成 ByteBuf (不复制内存，直接使用原数组)
        channel.writeAndFlush(buffer);
        return true;
    }

    public ElevatorResult getLastElevatorResult() {
        return lastElevatorResult;
    }


    /**
     * 电梯消息处理器
     */
    private class ElevatorMessageHandler extends ByteToMessageDecoder {
        private int failCount;

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
            log.info("[电梯] 连接断开");
            reconnect();
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
                String[] currentOccupyElevatorUser = LogicHandler.getInstance().getCurrentOccupyElevatorUser();
                if (currentOccupyElevatorUser != null) {
                    ElevatorCommand command = ElevatorCommand.buildToElevatorMsg((byte) 0x00, (byte) 0x12, (byte) 0x00);
                    log.info("连续5秒没有写操作,发送独占,保持独占信息 {}",command);
                    ByteBuf buffer = Unpooled.wrappedBuffer(command.getBytes());//将 byte[] 包装成 ByteBuf (不复制内存，直接使用原数组)
                    ctx.writeAndFlush(buffer);
                }
            }
        }
    }
}
