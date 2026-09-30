package org.nomad.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.nomad.bus.WakeBus;
import org.nomad.mailbox.MailboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Netty WebSocket gateway: ws://host:PORT/v1/ws (TLS is terminated by a reverse proxy for now). */
@Component
class WsGateway implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(WsGateway.class);
    private static final int MAX_FRAME = 2 * 1024 * 1024;

    private final int port;
    private final MailboxStore store;
    private final WakeBus bus;
    private final ObjectMapper mapper;
    private final Sessions sessions = new Sessions();

    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private ExecutorService exec;
    private Channel server;
    private Runnable unsubscribe;
    private volatile boolean running;

    WsGateway(@Value("${nomad.ws.port:8090}") int port, MailboxStore store, WakeBus bus, ObjectMapper mapper) {
        this.port = port;
        this.store = store;
        this.bus = bus;
        this.mapper = mapper;
    }

    @Override
    public synchronized void start() {
        exec = Executors.newVirtualThreadPerTaskExecutor();
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        GatewayHandler handler = new GatewayHandler(store, bus, sessions, mapper, exec);
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(MAX_FRAME),
                                new IdleStateHandler(120, 0, 0),
                                new WebSocketServerProtocolHandler("/v1/ws", null, false, MAX_FRAME),
                                handler);
                    }
                });
        server = b.bind(port).syncUninterruptibly().channel();
        unsubscribe = bus.subscribe(sessions::wake);
        running = true;
        LOG.info("WebSocket gateway listening on ws://0.0.0.0:{}/v1/ws", port);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        unsubscribe.run();
        server.close().syncUninterruptibly();
        boss.shutdownGracefully();
        workers.shutdownGracefully();
        exec.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
