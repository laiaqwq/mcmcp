package dev.mcmcp.client;

import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.domain.command.CommandResult;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.observability.McmcpLogger;
import dev.mcmcp.util.TimeUtil;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Command port implementation. Schedules command execution on the client thread
 * and uses Minecraft's high-level command sending entry point.
 * See PRD §7.1 and IMPLEMENTATION.md §11.1.
 */
public final class MinecraftCommandAdapter implements MinecraftPorts.CommandPort {

    private final MinecraftClientScheduler scheduler;
    private final ClientSessionTracker sessionTracker;

    public MinecraftCommandAdapter(MinecraftClientScheduler scheduler, ClientSessionTracker sessionTracker) {
        this.scheduler = scheduler;
        this.sessionTracker = sessionTracker;
    }

    @Override
    public CompletableFuture<MinecraftPorts.Result<CommandResult, ToolError>> execute(
        String commandWithoutSlash, long lifecycleGeneration, long deadlineNanos
    ) {
        return scheduler.submit(() -> {
            if (sessionTracker.currentGeneration() != lifecycleGeneration) {
                return MinecraftPorts.Result.err(ToolError.of(ToolErrorCode.GAME_NOT_READY,
                    "session changed during command scheduling"));
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return MinecraftPorts.Result.err(ToolError.of(ToolErrorCode.GAME_NOT_READY,
                    "player not in a world"));
            }

            LocalPlayer player = mc.player;
            ClientPacketListener handler = mc.getConnection();
            if (handler == null) {
                return MinecraftPorts.Result.err(ToolError.of(ToolErrorCode.CONNECTION_NOT_AVAILABLE,
                    "network connection not available"));
            }

            try {
                handler.sendCommand(commandWithoutSlash);

                String submittedAt = TimeUtil.nowUtc();
                String fullCommand = "/" + commandWithoutSlash;
                var result = new CommandResult(CommandResult.STATUS_SUBMITTED, fullCommand, submittedAt);

                player.sendSystemMessage(Component.literal("MCMCP submitted a command"));

                McmcpLogger.debug("command_submitted",
                    "command_length", String.valueOf(commandWithoutSlash.length()));
                return MinecraftPorts.Result.ok(result);
            } catch (Exception e) {
                McmcpLogger.error("command_rejected", "error", e.getMessage());
                return MinecraftPorts.Result.err(ToolError.of(ToolErrorCode.COMMAND_REJECTED,
                    "client-side command preparation failed"));
            }
        }, deadlineNanos).handle((result, throwable) -> {
            MinecraftPorts.Result<CommandResult, ToolError> captured =
                (MinecraftPorts.Result<CommandResult, ToolError>) result;
            if (throwable != null) {
                if (throwable instanceof java.util.concurrent.TimeoutException) {
                    McmcpLogger.debug("command_timeout");
                    return MinecraftPorts.Result.<CommandResult, ToolError>err(ToolError.of(ToolErrorCode.TIMEOUT,
                        "command submission timed out"));
                }
                return MinecraftPorts.Result.<CommandResult, ToolError>err(ToolError.internal("internal error"));
            }
            return captured;
        });
    }
}
