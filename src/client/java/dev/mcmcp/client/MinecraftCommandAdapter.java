package dev.mcmcp.client;

import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.domain.command.CommandResult;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.observability.McmcpLogger;
import dev.mcmcp.util.TimeUtil;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

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
            Consumer<String> messageSink = reason -> player.sendSystemMessage(Component.literal("MCMCP: " + reason));

            // Singleplayer-only guards: a paused world never ticks the integrated
            // server, so a submitted command would sit silently in the queue.
            IntegratedServer server = mc.getSingleplayerServer();
            boolean serverPaused = server != null && server.isPaused();
            Optional<MinecraftPorts.Result<CommandResult, ToolError>> preflight = checkSingleplayer(
                mc.isLocalServer(), mc.isPaused(), serverPaused,
                resolveCommandsAllowed(mc, player), messageSink
            );
            if (preflight.isPresent()) {
                return preflight.get();
            }

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
                messageSink.accept("command submission failed: " + e.getMessage());
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

    /**
     * Testable singleplayer pre-flight check. Returns empty when the command may
     * proceed; otherwise a rejected result with an appropriate {@link ToolErrorCode}.
     *
     * @param isLocalServer      whether the current world is a singleplayer/integrated server
     * @param isPaused           whether the client-side pause menu is active
     * @param isServerPaused     whether the integrated server itself is paused
     * @param commandsAllowed    whether command execution is allowed in this world
     * @param messageSink        receiver for player-facing rejection messages; may be null
     */
    static Optional<MinecraftPorts.Result<CommandResult, ToolError>> checkSingleplayer(
        boolean isLocalServer,
        boolean isPaused,
        boolean isServerPaused,
        boolean commandsAllowed,
        Consumer<String> messageSink
    ) {
        if (!isLocalServer) {
            return Optional.empty();
        }
        if (isPaused || isServerPaused) {
            return Optional.of(reject(messageSink, ToolErrorCode.GAME_NOT_READY,
                "command not submitted: the singleplayer world is paused — resume the game and try again"));
        }
        if (!commandsAllowed) {
            return Optional.of(reject(messageSink, ToolErrorCode.COMMAND_REJECTED,
                "command not submitted: commands are disabled for this world (Allow Commands is off)"));
        }
        return Optional.empty();
    }

    /**
     * Whether command execution is allowed when neither world-creation flag nor
     * LAN-granted operator permission would permit it.
     */
    static boolean commandsAllowed(boolean serverAllowsCommands, boolean playerHasCommandPermission) {
        return serverAllowsCommands || playerHasCommandPermission;
    }

    /**
     * Reads the live Minecraft world/player state to determine whether commands are
     * allowed. This is a thin, untested shell around values that can be driven
     * directly via {@link #commandsAllowed(boolean, boolean)}.
     */
    private boolean resolveCommandsAllowed(Minecraft mc, LocalPlayer player) {
        IntegratedServer server = mc.getSingleplayerServer();
        boolean serverAllowsCommands = false;
        if (server != null) {
            try {
                serverAllowsCommands = server.getWorldData().isAllowCommands();
            } catch (Throwable ignored) {}
        }
        boolean playerHasCommandPermission = player.permissions().hasPermission(
            new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
        return commandsAllowed(serverAllowsCommands, playerHasCommandPermission);
    }

    static MinecraftPorts.Result<CommandResult, ToolError> reject(
        Consumer<String> messageSink, ToolErrorCode code, String reason
    ) {
        if (messageSink != null) {
            messageSink.accept(reason);
        }
        McmcpLogger.debug("command_rejected_precheck", "code", code.name(), "reason", reason);
        return MinecraftPorts.Result.err(ToolError.of(code, reason));
    }
}
