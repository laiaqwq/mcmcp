package dev.mcmcp.util;

/**
 * Cancellation token for cooperative request cancellation.
 * Thread-safe, single-use: once cancelled, stays cancelled.
 */
public final class CancellationToken {
    private volatile boolean cancelled = false;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void throwIfCancelled() {
        if (cancelled) {
            throw new CancelledException();
        }
    }

    public static class CancelledException extends RuntimeException {}
}
