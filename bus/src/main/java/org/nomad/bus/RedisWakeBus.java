package org.nomad.bus;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wake-up bus on Redis Pub/Sub. Delivery is best effort by design: the source of truth is MailboxStore,
 * a client that missed a signal catches up by cursor. If publishing fails, only local sessions are woken.
 */
public final class RedisWakeBus implements WakeBus, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RedisWakeBus.class.getName());
    static final String CHANNEL = "nomad.wake";

    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> pub;
    private final StatefulRedisPubSubConnection<String, String> sub;

    public RedisWakeBus(String redisUri) {
        this.client = RedisClient.create(redisUri);
        this.pub = client.connect();
        this.sub = client.connectPubSub();
        this.sub.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
                dispatch(message);
            }
        });
        this.sub.sync().subscribe(CHANNEL);
    }

    @Override
    public void publish(String mailboxId) {
        pub.async().publish(CHANNEL, mailboxId).whenComplete((n, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "redis publish failed, waking local sessions only", err);
                dispatch(mailboxId);
            }
        });
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
        sub.close();
        pub.close();
        client.shutdown();
    }
}
