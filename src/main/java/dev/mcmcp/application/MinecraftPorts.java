package dev.mcmcp.application;

import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatQueryResult;
import dev.mcmcp.domain.command.CommandResult;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.screenshot.ScreenshotResult;
import dev.mcmcp.domain.state.GameStateSnapshot;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Port interfaces for the Minecraft client adapter. The application layer
 * depends on these, not on Minecraft classes. The client source set provides
 * the implementations. See IMPLEMENTATION.md §4.1.
 */
public final class MinecraftPorts {

    /** Execute a slash command on the client thread. */
    public interface CommandPort {
        /**
         * @param commandWithoutSlash command with leading '/' already removed
         * @param lifecycleGeneration generation captured at request time
         * @return future completing with the command result or a tool error
         */
        CompletableFuture<Result<CommandResult, ToolError>> execute(
            String commandWithoutSlash, long lifecycleGeneration, long deadlineNanos
        );
    }

    /** Query the bounded chat buffer. */
    public interface ChatPort {
        ChatQueryResult query(Long afterId, int limit, List<ChatMessage.MessageType> types);
    }

    /** Collect a game state snapshot on the client thread. */
    public interface StatePort {
        CompletableFuture<Result<GameStateSnapshot, ToolError>> snapshot(
            List<String> sections, long deadlineNanos
        );
    }

    /** Capture a screenshot from the render thread. */
    public interface ScreenshotPort {
        CompletableFuture<Result<ScreenshotResult, ToolError>> capture(
            int maxWidth, long deadlineNanos
        );
    }

    /** Get the current lifecycle generation. */
    public interface LifecyclePort {
        long currentGeneration();
    }

    /**
     * Either a success value or a tool error.
     */
    public record Result<T, E>(T value, E error) {
        public static <T, E> Result<T, E> ok(T value) { return new Result<>(value, null); }
        public static <T, E> Result<T, E> err(E error) { return new Result<>(null, error); }
        public boolean isSuccess() { return error == null; }
    }

    private MinecraftPorts() {}
}
