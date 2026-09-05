package dev.mcmcp.application;

import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatQueryResult;
import dev.mcmcp.domain.command.CommandResult;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.screenshot.ScreenshotResult;
import dev.mcmcp.domain.state.GameStateSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * Hand-rolled fake port implementations for the application-layer tests.
 */
public final class Fakes {

    private Fakes() {}

    public static final class FakeCommandPort implements MinecraftPorts.CommandPort {
        public record Call(String command, long generation, long deadline) {}

        public final List<Call> calls = new ArrayList<>();
        public final Queue<MinecraftPorts.Result<CommandResult, ToolError>> results = new ArrayDeque<>();
        public final Queue<CompletableFuture<MinecraftPorts.Result<CommandResult, ToolError>>> futures = new ArrayDeque<>();

        @Override
        public CompletableFuture<MinecraftPorts.Result<CommandResult, ToolError>> execute(
            String commandWithoutSlash, long lifecycleGeneration, long deadlineNanos
        ) {
            calls.add(new Call(commandWithoutSlash, lifecycleGeneration, deadlineNanos));
            if (!futures.isEmpty()) return futures.poll();
            MinecraftPorts.Result<CommandResult, ToolError> result = results.isEmpty()
                ? MinecraftPorts.Result.ok(new CommandResult(
                    CommandResult.STATUS_SUBMITTED,
                    "/" + commandWithoutSlash,
                    "2024-01-01T00:00:00Z"
                ))
                : results.poll();
            return CompletableFuture.completedFuture(result);
        }
    }

    public static final class FakeChatPort implements MinecraftPorts.ChatPort {
        public record Call(Long afterId, int limit, List<ChatMessage.MessageType> types) {}

        public final List<Call> calls = new ArrayList<>();
        public ChatQueryResult result;

        @Override
        public ChatQueryResult query(Long afterId, int limit, List<ChatMessage.MessageType> types) {
            calls.add(new Call(afterId, limit, types));
            if (result == null) {
                return new ChatQueryResult(
                    List.of(), null, null, null, false, false, 0L
                );
            }
            return result;
        }
    }

    public static final class FakeStatePort implements MinecraftPorts.StatePort {
        public record Call(List<String> sections, long deadline) {}

        public final List<Call> calls = new ArrayList<>();
        public final Queue<MinecraftPorts.Result<GameStateSnapshot, ToolError>> results = new ArrayDeque<>();
        public final Queue<CompletableFuture<MinecraftPorts.Result<GameStateSnapshot, ToolError>>> futures = new ArrayDeque<>();

        @Override
        public CompletableFuture<MinecraftPorts.Result<GameStateSnapshot, ToolError>> snapshot(
            List<String> sections, long deadlineNanos
        ) {
            calls.add(new Call(new ArrayList<>(sections), deadlineNanos));
            if (!futures.isEmpty()) return futures.poll();
            MinecraftPorts.Result<GameStateSnapshot, ToolError> result = results.isEmpty()
                ? MinecraftPorts.Result.ok(defaultSnapshot())
                : results.poll();
            return CompletableFuture.completedFuture(result);
        }

        private static GameStateSnapshot defaultSnapshot() {
            return fullSnapshot();
        }
    }

    public static final class FakeScreenshotPort implements MinecraftPorts.ScreenshotPort {
        public record Call(int maxWidth, long deadline) {}

        public final List<Call> calls = new ArrayList<>();
        public final Queue<MinecraftPorts.Result<ScreenshotResult, ToolError>> results = new ArrayDeque<>();
        public final Queue<CompletableFuture<MinecraftPorts.Result<ScreenshotResult, ToolError>>> futures = new ArrayDeque<>();

        @Override
        public CompletableFuture<MinecraftPorts.Result<ScreenshotResult, ToolError>> capture(
            int maxWidth, long deadlineNanos
        ) {
            calls.add(new Call(maxWidth, deadlineNanos));
            if (!futures.isEmpty()) return futures.poll();
            MinecraftPorts.Result<ScreenshotResult, ToolError> result = results.isEmpty()
                ? MinecraftPorts.Result.ok(new ScreenshotResult(
                    "2024-01-01T00:00:00Z",
                    800, 600, "image/png",
                    1920, 1080,
                    new byte[] {1, 2, 3}
                ))
                : results.poll();
            return CompletableFuture.completedFuture(result);
        }
    }

    public static final class FakeLifecyclePort implements MinecraftPorts.LifecyclePort {
        public long generation = 42L;

        @Override
        public long currentGeneration() {
            return generation;
        }
    }

    public static GameStateSnapshot clientSnapshot() {
        return new GameStateSnapshot(
            "2024-01-01T00:00:00Z",
            1L,
            true,
            new GameStateSnapshot.ClientSection(
                "1.20.6", "0.1.0", 60, "play", false, true, 2
            ),
            null, null, null, null, null
        );
    }

    public static GameStateSnapshot fullSnapshot() {
        GameStateSnapshot.Vec3 origin = new GameStateSnapshot.Vec3(1.5, 2.0, 3.5);
        GameStateSnapshot.Vec3i block = new GameStateSnapshot.Vec3i(1, 2, 3);
        GameStateSnapshot.Vec3i chunk = new GameStateSnapshot.Vec3i(0, 0, 0);
        GameStateSnapshot.Vec3i inChunk = new GameStateSnapshot.Vec3i(1, 2, 3);
        GameStateSnapshot.Vec3i chunkOrigin = new GameStateSnapshot.Vec3i(0, 0, 0);

        GameStateSnapshot.ClientSection client = new GameStateSnapshot.ClientSection(
            "1.20.6", "0.1.0", 60, "play", false, true, 2
        );
        GameStateSnapshot.ConnectionSection connection = new GameStateSnapshot.ConnectionSection(
            true, "multiplayer", "mc.example.com", 30
        );
        GameStateSnapshot.Rotation rotation = new GameStateSnapshot.Rotation(0.0, 0.0);
        GameStateSnapshot.SelectedItem selected = new GameStateSnapshot.SelectedItem(
            "minecraft:stone", 1, "Stone"
        );
        GameStateSnapshot.PlayerSection player = new GameStateSnapshot.PlayerSection(
            "Steve", "00000000-0000-0000-0000-000000000000", "survival",
            origin, block, rotation, new GameStateSnapshot.Vec3(0.0, 0.0, 0.0),
            true, false, false, false, false,
            20.0, 20.0, 0.0, 20, 0.0, 300, 300, 0, 0, 0.0,
            0, selected, List.of()
        );
        GameStateSnapshot.Light light = new GameStateSnapshot.Light(15, 15);
        GameStateSnapshot.WorldSection world = new GameStateSnapshot.WorldSection(
            "minecraft:overworld", "plains", "normal", false,
            1L, 1000L, false, false, light
        );
        GameStateSnapshot.Coordinates coordinates = new GameStateSnapshot.Coordinates(
            origin, block, chunk, inChunk, chunkOrigin, new GameStateSnapshot.Region(0, 0)
        );
        GameStateSnapshot.Facing facing = new GameStateSnapshot.Facing(
            "north", "z", "south", 0.0, 0.0
        );
        GameStateSnapshot.Heightmaps heightmaps = new GameStateSnapshot.Heightmaps(100, 80);
        GameStateSnapshot.ChunkInfo chunkInfo = new GameStateSnapshot.ChunkInfo(
            true, "full", 0.5, 0L, heightmaps
        );
        GameStateSnapshot.RenderInfo render = new GameStateSnapshot.RenderInfo(
            10, 10, 60, 16.6, 100, 5, 6, 0
        );
        GameStateSnapshot.SystemInfo system = new GameStateSnapshot.SystemInfo(
            "25", 100L, 200L, 400L, "CPU", "GPU", 1920, 1080, "opengl"
        );
        GameStateSnapshot.DebugSection debug = new GameStateSnapshot.DebugSection(
            coordinates, facing, chunkInfo, render, system
        );
        GameStateSnapshot.BlockTarget blockTarget = new GameStateSnapshot.BlockTarget(
            "minecraft:stone", block, "up", Map.of(), List.of()
        );
        GameStateSnapshot.TargetSection target = new GameStateSnapshot.TargetSection(
            "block", 3.0, blockTarget, null, null
        );

        return new GameStateSnapshot(
            "2024-01-01T00:00:00Z", 0L, true,
            client, connection, player, world, debug, target
        );
    }
}
