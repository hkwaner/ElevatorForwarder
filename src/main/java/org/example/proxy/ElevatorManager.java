package org.example.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 电梯连接管理器 - 管理与电梯的单连接
 */
public class ElevatorManager {
    private final String host;
    private final int port;
    private EventLoopGroup workerGroup;
    private Channel channel;
    private volatile boolean needReconnect = true;
    
    // 消息回调：收到电梯消息时通知代理服务器
    private Consumer<ProxyMessage> messageHandler;
    
    // 重连配置
    private int reconnectDelay = 5;
    private final int maxReconnectDelay = 60;
    
    public ElevatorManager(String host, int port) {
        this.host = host;
        this.port = port;
    }
    
    /**
     * 设置消息处理器
     */
    public void setMessageHandler(Consumer<ProxyMessage> handler) {
        this.messageHandler = handler;
    }
    
    /**
     * 启动并连接电梯
     */
    public void start() {
        workerGroup = new NioEventLoopGroup();
        doConnect();
    }
    
    private void doConnect() {
        if (!needReconnect) {
            return;
        }
        
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
                                .addLast(new FixedLengthFrameDecoder(6))  // 电梯广播：6字节
                                .addLast(new ElevatorMessageHandler());
                    }
                });
        
        System.out.println("[电梯] 正在连接: " + host + ":" + port);
        
        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                channel = future.channel();
                System.out.println("[电梯] 连接成功");
                reconnectDelay = 5;
            } else {
                System.out.println("[电梯] 连接失败: " + future.cause().getMessage());
                scheduleReconnect();
            }
        });
    }
    
    private void scheduleReconnect() {
        if (!needReconnect) {
            return;
        }
        
        System.out.println("[电梯] " + reconnectDelay + "秒后重连...");
        workerGroup.schedule(() -> doConnect(), reconnectDelay, TimeUnit.SECONDS);
        reconnectDelay = Math.min(reconnectDelay * 2, maxReconnectDelay);
    }
    
    /**
     * 发送消息给电梯
     */
    public void sendMessage(ProxyMessage msg) {
        if (channel != null && channel.isActive()) {
            byte[] data = msg.toElevatorBytes();
            channel.writeAndFlush(Unpooled.wrappedBuffer(data));
            System.out.println("[电梯] 发送消息: " + msg);
        } else {
            System.out.println("[电梯] 连接未建立，无法发送");
        }
    }
    
    /**
     * 停止
     */
    public void stop() {
        needReconnect = false;
        if (channel != null) {
            channel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        System.out.println("[电梯] 已停止");
    }
    
    public boolean isConnected() {
        return channel != null && channel.isActive();
    }
    
    /**
     * 电梯消息处理器
     */
    private class ElevatorMessageHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            byte[] data = new byte[6];
            msg.readBytes(data);
            
            ProxyMessage proxyMsg = ProxyMessage.fromElevatorBytes(data);
            if (proxyMsg == null) {
                System.out.println("[电梯] 消息解析失败");
                return;
            }
            
            if (!proxyMsg.validateCrc()) {
                System.out.println("[电梯] CRC校验失败");
                return;
            }
            
            System.out.println("[电梯] 收到消息: " + proxyMsg);
            
            // 回调通知代理服务器
            if (messageHandler != null) {
                messageHandler.accept(proxyMsg);
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            System.out.println("[电梯] 连接断开");
            scheduleReconnect();
            super.channelInactive(ctx);
        }
    }
}
