package org.nomad.server.ws;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** All limits of one connection (one device). Every check returns 0 if allowed, else the wait in milliseconds. */
final class Limiter {
    private static final int MAX_TARGETS = 64;

    private final Limits limits;
    private final TokenBucket send;
    private final TokenBucket read;
    private final TokenBucket pk;
    private final AtomicInteger violations = new AtomicInteger();
    private final Map<String, TokenBucket> perTarget = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TokenBucket> eldest) {
            return size() > MAX_TARGETS;
        }
    };

    Limiter(Limits limits) {
        this.limits = limits;
        this.send = new TokenBucket(limits.sendBurst(), limits.sendPerSec());
        this.read = new TokenBucket(limits.readBurst(), limits.readPerSec());
        this.pk = new TokenBucket(limits.pkPerMin(), limits.pkPerMin() / 60.0);
    }

    long send() {
        return send.tryTake(System.nanoTime());
    }

    long read() {
        return read.tryTake(System.nanoTime());
    }

    /** A device may not drain the one-time prekeys of one target: separate, tighter bucket per target uid. */
    long pkGet(String targetUid) {
        long now = System.nanoTime();
        TokenBucket t;
        synchronized (perTarget) {
            t = perTarget.computeIfAbsent(
                    targetUid, k -> new TokenBucket(limits.pkTargetPerMin(), limits.pkTargetPerMin() / 60.0));
        }
        long wait = t.tryTake(now);
        if (wait > 0) {
            return wait;
        }
        return pk.tryTake(now);
    }

    int violation() {
        return violations.incrementAndGet();
    }
}
