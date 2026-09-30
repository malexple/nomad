package org.nomad.server.ws;

/** Classic token bucket driven by an explicit clock (nanoseconds) so that it can be tested. */
final class TokenBucket {
    private final double capacity;
    private final double perNano;
    private double tokens;
    private long last;
    private boolean started;

    TokenBucket(double capacity, double perSecond) {
        this.capacity = capacity;
        this.perNano = perSecond / 1e9;
        this.tokens = capacity;
    }

    /** @return 0 if a token was taken, otherwise the number of milliseconds until one is available */
    synchronized long tryTake(long nowNanos) {
        if (!started) {
            last = nowNanos;
            started = true;
        }
        tokens = Math.min(capacity, tokens + (nowNanos - last) * perNano);
        last = nowNanos;
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return 0;
        }
        return Math.max(1, (long) Math.ceil((1.0 - tokens) / perNano / 1e6));
    }
}
