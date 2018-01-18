package com.tuling.netty.snake_game;

import com.alibaba.fastjson.JSON;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.beans.BeanUtils;

import java.util.Arrays;

/**
 * Websocket 聊天服务器-服务端
 */
public class SnakeGameServer {

    private int port;
    final SnakeGameEngine gameEngine;
    private final ChannelGroup channels;

    public SnakeGameServer(int port) {
        this.port = port;
        gameEngine = new SnakeGameEngine(80, 80, 200);
        channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    }

    public void run() throws Exception {
        // 启动 游戏引擎
        gameEngine.start();
        gameEngine.setListener(new SnakeGameEngine.SnakeGameListener() {
            @Override
            public void versionChange(VersionData changeData, VersionData currentData) {
                sendVersionData(changeData);
            }
        });

        EventLoopGroup bossGroup = new NioEventLoopGroup(2); // (1)
        EventLoopGroup workerGroup = new NioEventLoopGroup(3);
        try {
            ServerBootstrap b = new ServerBootstrap(); // (2)
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class) // (3)
                    .childHandler(new ChannelInitializer() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast("http-decodec", new HttpRequestDecoder());
                            pipeline.addLast("http-aggregator", new HttpObjectAggregator(65536));
                            pipeline.addLast("http-encodec", new HttpResponseEncoder());
                            pipeline.addLast("http-chunked", new ChunkedWriteHandler());
                            pipeline.addLast("http-request", new HttpRequestHandler("/ws"));
                            pipeline.addLast("WebSocket-protocol", new WebSocketServerProtocolHandler("/ws"));
                            pipeline.addLast("WebSocket-request", new SnakeGameDataSynchHandler(gameEngine, channels));
                        }
                    })  //(4)
                    .option(ChannelOption.SO_BACKLOG, 128)          // (5)
                    .childOption(ChannelOption.SO_KEEPALIVE, true); // (6)

            System.out.println("SnakeGameServer 启动了" + port);
            // 绑定端口，开始接收进来的连接
            ChannelFuture f = b.bind(port).sync(); // (7)
            // 等待服务器  socket 关闭 。
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            System.out.println("SnakeGameServer 关闭了");
        }
    }

    private void sendVersionData(VersionData data) {
        VersionData copy=new VersionData(); // 副本
        BeanUtils.copyProperties(data,copy);
        String str = JSON.toJSONString(data);
        String[] cmds,cmdDatas;
        for (Channel channel : channels) {
            DrawingCommand cmd = gameEngine.getDrawingCommand(channel.id().asShortText());
            if (cmd != null) {
                cmds = Arrays.copyOf(data.getCmds(), data.getCmds().length + 1);
                cmds[cmds.length - 1] = cmd.getCmd();
                cmdDatas = Arrays.copyOf(data.getCmdDatas(), data.getCmdDatas().length + 1);
                cmdDatas[cmdDatas.length - 1] = cmd.getCmdData();
                copy.setCmds(cmds);
                copy.setCmdDatas(cmdDatas);
                channel.writeAndFlush(new TextWebSocketFrame(JSON.toJSONString(copy)));
            } else {
                channel.writeAndFlush(new TextWebSocketFrame(str));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        } else {
            port = 8080;
        }
        new SnakeGameServer(port).run();

    }
}