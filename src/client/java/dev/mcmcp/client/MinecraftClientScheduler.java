package dev.mcmcp.client;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Schedules tasks onto the Minecraft client thread and returns futures that complete
 * with the result or a timeout. See IMPLEMENTATION.md §5, §11.
 *
 * <p>Uses {@link Minecraft#getInstance()} to access the client thread:
 * <ul>
 *   <li>{@link Minecraft#execute(Runnable)} submits a task to the client thread queue.</li>
 *   <li>{@link Minecraft#isSameThread()} checks whether the caller is already on the client thread.</li>
 * </ul>
 *
 * <p>Tasks are never run on the calling thread; they are always dispatched to the client
 * thread via {@code execute()}, even when the caller is already on it. This keeps the
 * execution model uniform and avoids re-entrancy surprises in the supplier.
 */
public final class MinecraftClientScheduler {

    private final long defaultTimeoutNanos;

    /**
     * @param defaultTimeoutNanos fallback deadline used when {@code deadlineNanos <= 0};
     *                            {@code <= 0} disables the default timeout entirely.
     */
    public MinecraftClientScheduler(long defaultTimeoutNanos) {
        this.defaultTimeoutNanos = defaultTimeoutNanos;
    }

    /**
     * Submit a task to run on the Minecraft client thread.
     *
     * <p>The supplier runs on the client thread and must not block. If the deadline
     * elapses before the task is picked up (or while it runs), the returned future
     * completes exceptionally with a {@link TimeoutException}. The future may also
     * be cancelled by the caller via {@link CompletableFuture#cancel(boolean)}.
     *
     * @param task         supplier executed on the client thread
     * @param deadlineNanos absolute deadline in nanos (System.nanoTime()-compatible),
     *                      or {@code <= 0} to use the scheduler default
     * @return a future that completes with the supplier result or a timeout
     */
    public <T> CompletableFuture<T> submit(Supplier<T> task, long deadlineNanos) {
        CompletableFuture<T> future = new CompletableFuture<>();
        long timeout = deadlineNanos > 0 ? deadlineNanos : defaultTimeoutNanos;
        long startNanos = System.nanoTime();

        Minecraft client = Minecraft.getInstance();

        client.execute(() -> {
            if (future.isDone()) return; // already cancelled or timed out
            if (timeout > 0) {
                long remaining = timeout - (System.nanoTime() - startNanos);
                if (remaining <= 0) {
                    future.completeExceptionally(
                            new TimeoutException("client thread deadline exceeded"));
                    return;
                }
            }
            try {
                T result = task.get();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        // Schedule a timeout watchdog on the common pool.
        if (timeout > 0) {
            CompletableFuture.delayedExecutor(remainingMillis(timeout, startNanos), TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (!future.isDone()) {
                            future.completeExceptionally(
                                    new TimeoutException("client thread timeout"));
                        }
                    });
        }

        return future;
    }

    /**
     * Submit a task using the scheduler's default timeout.
     */
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        return submit(task, 0L);
    }

    /**
     * Run a task synchronously if the caller is already on the client thread, otherwise
     * submit it and block the caller until it completes or the deadline elapses.
     *
     * @throws TimeoutException if the deadline elapses before the task completes
     */
    public <T> T submitAndWait(Supplier<T> task, long deadlineNanos) throws TimeoutException {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            return task.get();
        }
        long timeout = deadlineNanos > 0 ? deadlineNanos : defaultTimeoutNanos;
        CompletableFuture<T> future = submit(task, deadlineNanos);
        try {
            if (timeout > 0) {
                return future.get(timeout, TimeUnit.NANOSECONDS);
            }
            return future.get();
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error er) throw er;
            throw new RuntimeException(e);
        }
    }

    /**
     * Submit a no-result task to the client thread.
     */
    public CompletableFuture<Void> run(Runnable task, long deadlineNanos) {
        return submit(() -> {
            task.run();
            return null;
        }, deadlineNanos);
    }

    private long remainingMillis(long timeoutNanos, long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        long remaining = timeoutNanos - elapsed;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }
}
