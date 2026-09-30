package org.nomad.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TokenBucketTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void burstThenWaitThenRefill() {
        TokenBucket b = new TokenBucket(3, 1.0);
        assertEquals(0, b.tryTake(0));
        assertEquals(0, b.tryTake(0));
        assertEquals(0, b.tryTake(0));

        long wait = b.tryTake(0);
        assertTrue(wait >= 900 && wait <= 1100, "wait was " + wait);

        assertEquals(0, b.tryTake(SECOND + SECOND / 10));
        assertTrue(b.tryTake(SECOND + SECOND / 10) > 0);
    }

    @Test
    void tokensAreCappedAtTheBurstSize() {
        TokenBucket b = new TokenBucket(2, 10.0);
        assertEquals(0, b.tryTake(0));
        assertEquals(0, b.tryTake(1000 * SECOND));
        assertEquals(0, b.tryTake(1000 * SECOND));
        assertTrue(b.tryTake(1000 * SECOND) > 0);
    }

    @Test
    void limiterPerTargetBucketIsTighterThanTheGlobalOne() {
        Limiter l = new Limiter(new Limits(20, 50, 50, 100, 10, 3));
        assertEquals(0, l.pkGet("aaa"));
        assertEquals(0, l.pkGet("aaa"));
        assertEquals(0, l.pkGet("aaa"));
        assertTrue(l.pkGet("aaa") > 0, "fourth lookup of the same target must be refused");
        assertEquals(0, l.pkGet("bbb"), "another target is still allowed");
    }
}
