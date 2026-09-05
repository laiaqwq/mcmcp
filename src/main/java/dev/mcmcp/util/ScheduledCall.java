package dev.mcmcp.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A scheduled cross-thread call with deadline and cancellation support.
 * See IMPLEMENTATION.md §5.1.
 */
public final class ScheduledCall<T> {
    private static final AtomicLong ID_GEN = new AtomicLong(0);

    private final long requestId;
    private final long acceptedAtNanos;
    private final long deadlineNanos;
    private final long lifecycleGeneration;
    private final CancellationToken cancellation;
    private final CompletableFuture<T> future;

    public ScheduledCall(long deadlineNanos, long lifecycleGeneration, CancellationToken cancellation) {
        this.requestId = ID_GEN.incrementAndGet();
        this.acceptedAtNanos = System.nanoTime();
        this.deadlineNanos = deadlineNanos;
        this.lifecycleGeneration = lifecycleGeneration;
        this.cancellation = cancellation;
        this.future = new CompletableFuture<>();
    }

    public long requestId() { return requestId; }
    public long acceptedAtNanos() { return acceptedAtNanos; }
    public long deadlineNanos() { return deadlineNanos; }
    public long lifecycleGeneration() { return lifecycleGeneration; }
    public CancellationToken cancellation() { return cancellation; }
    public CompletableFuture<T> future() { return future; }

    public boolean isExpired() {
        return System.nanoTime() > deadlineNanos;
    }

    public boolean isDone() {
        return future.isDone() || cancellation.isCancelled() || isExpired();
    }
}
