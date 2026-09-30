package org.nomad.bus;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wake-up bus on Redis Pub/Sub. Delivery is best effort by design: the source of truth is MailboxStore,
 * a client that missed a signal catches up by cursor.
 * The node starts even if Redis is down: the connection is retried in the background, and while it is
 * missing (or a publish fails) only the local sessions are woken.
 * After every reconnect the special mailbox id "*" is dispatched: the node wakes all its local sessions,
 * so signals lost while Redis was unavailable are recovered without waiting for client polling.
 */
public final class RedisWakeBus implements WakeBus, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RedisWakeBus.class.getName());
    static final String CHANNEL = "nomad.wake";
    private static final String WAKE_ALL = "*";
    private static final int RETRY_SECONDS = 2;

    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final RedisClient client;
    private final ScheduledExecutorService connector = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "redis-wake-connector");
        t.setDaemon(true);
        return t;
    });
    private volatile StatefulRedisConnection<String, String> pub;
    private volatile StatefulRedisPubSubConnection<String, String> sub;
    private volatile boolean ready;
    private volatile boolean closed;

    public RedisWakeBus(String redisUri) {
        this.client = RedisClient.create(redisUri);
        // Fail fast while Redis is down instead of queueing commands until it comes back.
        this.client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
                .build());
        this.client.addListener(new RedisConnectionStateListener() {
            @Override
            public void onRedisConnected(RedisChannelHandler<?, ?> connection, SocketAddress socketAddress) {
                if (ready) {
                    LOG.info("Redis reconnected, waking all local sessions");
                    dispatch(WAKE_ALL);
                }
            }

            @Override
            public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
                if (ready) {
                    LOG.warning("Redis connection lost");
                }
            }
        });
        connector.execute(this::tryConnect);
    }

    private void tryConnect() {
        if (closed) {
            return;
        }
        StatefulRedisConnection<String, String> p = null;
        try {
            p = client.connect();
            StatefulRedisPubSubConnection<String, String> s = client.connectPubSub();
            s.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    dispatch(message);
                }
            });
            s.sync().subscribe(CHANNEL);
            this.pub = p;
            this.sub = s;
            this.ready = true;
            LOG.info("connected to Redis, wake bus is active");
            dispatch(WAKE_ALL);
        } catch (RuntimeException e) {
            if (p != null) {
                p.close();
            }
            LOG.warning("Redis is not reachable, retrying in " + RETRY_SECONDS + " s: " + e.getMessage());
            if (!closed) {
                connector.schedule(this::tryConnect, RETRY_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void publish(String mailboxId) {
        StatefulRedisConnection<String, String> p = pub;
        if (p == null) {
            dispatch(mailboxId);
            return;
        }
        try {
            p.async().publish(CHANNEL, mailboxId).whenComplete((n, err) -> {
                if (err != null) {
                    LOG.log(Level.WARNING, "redis publish failed, waking local sessions only: " + err);
                    dispatch(mailboxId);
                }
            });
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "redis publish rejected, waking local sessions only: " + e);
            dispatch(mailboxId);
        }
    }

    @Override
    public Runnable subscribe(Consumer<String> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void dispatch(String mailboxId) {
        for (Consumer<String> l : listeners) {
            try {
                l.accept(mailboxId);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "wake listener failed", e);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        connector.shutdownNow();
        StatefulRedisPubSubConnection<String, String> s = sub;
        StatefulRedisConnection<String, String> p = pub;
        if (s != null) {
            s.close();
        }
        if (p != null) {
            p.close();
        }
        client.shutdown();
    }
}
