package org.example.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 机器人连接管理器 - 管理多个机器人客户端连接
 */
public class RobotManager {
    // 机器人信息类
    public static class RobotInfo {
        private final String robotId;
        private final Channel channel;
        private final String ip;
        private final int port;

        public RobotInfo(String robotId, Channel channel, String ip, int port) {
            this.robotId = robotId;
            this.channel = channel;
            this.ip = ip;
            this.port = port;
        }

        public String getRobotId() {
            return robotId;
        }

        public Channel getChannel() {
            return channel;
        }

        public String getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        @Override
        public String toString() {
            return robotId + " (" + ip + ":" + port + ")";
        }
    }

    public RobotManager() {

    }



    // 机器人连接映射：robotId -> RobotInfo
    private final Map<String, RobotInfo> robotChannels = new ConcurrentHashMap<>();

    // 消息回调：收到机器人消息时通知代理服务器
    private RobotMessageHandler messageHandler;

    /**
     * 消息处理器接口
     */
    public interface RobotMessageHandler {
        void onMessage(String robotId, ProxyMessage msg);

        void onConnect(String robotId);

        void onDisconnect(String robotId);
    }

    /**
     * 设置消息处理器
     */
    public void setMessageHandler(RobotMessageHandler handler) {
        this.messageHandler = handler;
    }

    /**
     * 注册机器人连接
     */
    public String registerRobot(Channel channel) {
//        String robotId = "robot-" + idGenerator.getAndIncrement();
        InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
        String ip = remoteAddress.getAddress().getHostAddress();
//        int port = remoteAddress.getPort();
//
//        robotChannels.put(robotId, new RobotInfo(robotId, channel, ip, port));
//        System.out.println("[机器人] " + robotId + " 已连接，IP: " + ip + ":" + port);
//
//        if (messageHandler != null) {
//            messageHandler.onConnect(robotId);
//        }

        return "";
    }

    /**
     * 移除机器人连接
     */
    public void removeRobot(String robotId) {
        RobotInfo info = robotChannels.remove(robotId);
        if (info != null) {
            System.out.println("[机器人] " + robotId + " 已断开，IP: " + info.getIp());
        }

        if (messageHandler != null) {
            messageHandler.onDisconnect(robotId);
        }
    }

    /**
     * 发送消息给指定机器人
     */
    public void sendToRobot(String robotId, ProxyMessage msg) {
        RobotInfo info = robotChannels.get(robotId);
        if (info != null && info.getChannel().isActive()) {
            byte[] data = msg.toRobotBytes();
            info.getChannel().writeAndFlush(Unpooled.wrappedBuffer(data));
            System.out.println("[机器人] 发送给 " + robotId + " (" + info.getIp() + "): " + msg);
        } else {
            System.out.println("[机器人] " + robotId + " 连接不存在");
        }
    }

    /**
     * 广播消息给所有机器人
     */
    public void broadcastToAll(ProxyMessage msg) {
        byte[] data = msg.toRobotBytes();
        for (RobotInfo info : robotChannels.values()) {
            Channel channel = info.getChannel();
            if (channel.isActive()) {
                channel.writeAndFlush(Unpooled.wrappedBuffer(data));
            }
        }
        System.out.println("[机器人] 广播给所有机器人：" + msg);
    }

    /**
     * 获取在线机器人数量
     */
    public int getOnlineCount() {
        return robotChannels.size();
    }

    /**
     * 获取所有在线机器人 ID
     */
    public Set<String> getOnlineRobots() {
        return robotChannels.keySet();
    }

    /**
     * 获取机器人信息
     */
    public RobotInfo getRobotInfo(String robotId) {
        return robotChannels.get(robotId);
    }

    /**
     * 创建机器人连接的 Pipeline 配置
     */
    public ChannelInitializer<io.netty.channel.socket.SocketChannel> createPipeline() {
        return new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
            @Override
            protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                ch.pipeline().addLast(new LoggingHandler(LogLevel.INFO)).addLast(new FixedLengthFrameDecoder(5))  // 机器人发送：5 字节
                        .addLast(new RobotMessageDecoder());
            }
        };
    }

    /**
     * 机器人消息解码器
     */
    private class RobotMessageDecoder extends SimpleChannelInboundHandler<ByteBuf> {
        private String robotId;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            robotId = registerRobot(ctx.channel());
            super.channelActive(ctx);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            byte[] data = new byte[5];
            msg.readBytes(data);

            ProxyMessage proxyMsg = ProxyMessage.fromRobotBytes(data);
            if (proxyMsg == null) {
                System.out.println("[机器人] " + robotId + " 消息解析失败");
                return;
            }

            if (!proxyMsg.validateCrc()) {
                System.out.println("[机器人] " + robotId + " CRC 校验失败");
                return;
            }

            proxyMsg.setSource(robotId);
            System.out.println("[机器人] " + robotId + " 收到消息：" + proxyMsg);

            // 回调通知代理服务器
            if (messageHandler != null) {
                messageHandler.onMessage(robotId, proxyMsg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (robotId != null) {
                removeRobot(robotId);
            }
            super.channelInactive(ctx);
        }
    }
}
