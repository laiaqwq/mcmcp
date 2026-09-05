package dev.mcmcp.observability;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight in-process metrics counters. Not exposed via a network endpoint.
 * See IMPLEMENTATION.md §13.2.
 */
public final class Metrics {
    private final LongAdder requestCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();
    private final AtomicLong activeRequests = new AtomicLong();
    private final LongAdder timeoutCount = new LongAdder();
    private final LongAdder cancellationCount = new LongAdder();
    private final LongAdder lateCompletionCount = new LongAdder();

    public void requestStarted() {
        requestCount.increment();
        activeRequests.incrementAndGet();
    }

    public void requestFinished() {
        activeRequests.decrementAndGet();
    }

    public void requestRejected() {
        rejectedCount.increment();
    }

    public void timeout() {
        timeoutCount.increment();
    }

    public void cancelled() {
        cancellationCount.increment();
    }

    public void lateCompletion() {
        lateCompletionCount.increment();
    }

    public long requestCount() { return requestCount.sum(); }
    public long rejectedCount() { return rejectedCount.sum(); }
    public long activeRequests() { return activeRequests.get(); }
    public long timeoutCount() { return timeoutCount.sum(); }
    public long cancellationCount() { return cancellationCount.sum(); }
    public long lateCompletionCount() { return lateCompletionCount.sum(); }
}
