package dev.mcmcp.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void allowsBurstUpToCapacity() {
        long[] time = {0};
        RateLimiter.NanoClock clock = () -> time[0];
        var limiter = new RateLimiter(5, clock);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "should allow burst " + i);
        }
        assertFalse(limiter.tryAcquire(), "should reject after capacity exhausted");
    }

    @Test
    void refillsAfterTime() {
        long[] time = {0};
        RateLimiter.NanoClock clock = () -> time[0];
        var limiter = new RateLimiter(10, clock);
        // Exhaust capacity
        for (int i = 0; i < 10; i++) limiter.tryAcquire();
        assertFalse(limiter.tryAcquire());
        // Advance 1 second
        time[0] = 1_000_000_000L;
        assertTrue(limiter.tryAcquire(), "should refill after 1 second");
    }
}
