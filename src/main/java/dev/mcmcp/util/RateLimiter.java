package dev.mcmcp.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token bucket rate limiter. Thread-safe. Uses a monotonic nano-time source
 * (injectable for testing). Capacity = rate (burst = 1 second worth).
 */
public final class RateLimiter {
    private final long capacity;
    private final double refillPerNano;
    private final NanoClock clock;
    private final AtomicLong tokens;
    private volatile long lastRefillNanos;

    public interface NanoClock {
        long nanoTime();
    }

    public RateLimiter(int requestsPerSecond, NanoClock clock) {
        this.capacity = requestsPerSecond;
        this.refillPerNano = requestsPerSecond / 1_000_000_000.0;
        this.clock = clock;
        this.tokens = new AtomicLong(requestsPerSecond);
        this.lastRefillNanos = clock.nanoTime();
    }

    public RateLimiter(int requestsPerSecond) {
        this(requestsPerSecond, System::nanoTime);
    }

    public boolean tryAcquire() {
        long now = clock.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            long refill = (long) (elapsed * refillPerNano);
            if (refill > 0) {
                lastRefillNanos = now;
                while (true) {
                    long current = tokens.get();
                    long newVal = Math.min(capacity, current + refill);
                    if (tokens.compareAndSet(current, newVal)) break;
                }
            }
        }
        while (true) {
            long current = tokens.get();
            if (current <= 0) return false;
            if (tokens.compareAndSet(current, current - 1)) return true;
        }
    }
}
