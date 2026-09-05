package dev.mcmcp.client;

import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.domain.command.CommandResult;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MinecraftCommandAdapter}.
 *
 * <p>The pause-world / permission pre-checks are exposed as package-private,
 * purely boolean decision methods so they can be exercised headless. The
 * {@code execute()} scheduling boundary still fails without a running client
 * because {@link MinecraftClientScheduler} dereferences
 * {@code Minecraft.getInstance()} before the supplier runs.
 */
class MinecraftCommandAdapterTest {

    private static Consumer<String> collectingSink(List<String> messages) {
        return messages::add;
    }

    @Test
    void executeCannotReachCommandLogicWithoutClient() {
        var scheduler = new MinecraftClientScheduler(1_000_000L);
        var tracker = new ClientSessionTracker();
        tracker.onJoin();
        var adapter = new MinecraftCommandAdapter(scheduler, tracker);

        assertNull(net.minecraft.client.Minecraft.getInstance(),
            "test precondition: no running client");

        // The scheduler dereferences the (null) client before the command
        // supplier — including the generation fence — ever executes.
        assertThrows(NullPointerException.class,
            () -> adapter.execute("say hi", tracker.currentGeneration(), 1_000_000L));
    }

    @Test
    void staleGenerationFenceIsInsideTheClientThreadSupplier() {
        var scheduler = new MinecraftClientScheduler(1_000_000L);
        var tracker = new ClientSessionTracker();
        tracker.onJoin(); // generation is now 1
        var adapter = new MinecraftCommandAdapter(scheduler, tracker);

        assertThrows(NullPointerException.class,
            () -> adapter.execute("kill", /* stale generation */ 0L, 1_000_000L));
    }

    @Test
    void pausedSingleplayerWorldRejectsWithGameNotReady() {
        List<String> messages = new ArrayList<>();

        Optional<MinecraftPorts.Result<CommandResult, ToolError>> result =
            MinecraftCommandAdapter.checkSingleplayer(
                true, true, false, true, collectingSink(messages));

        assertTrue(result.isPresent(), "guard must reject a paused singleplayer world");
        assertFalse(result.get().isSuccess());
        assertEquals(ToolErrorCode.GAME_NOT_READY, result.get().error().code());
        assertTrue(messages.get(0).contains("paused"));
    }

    @Test
    void pausedIntegratedServerRejectsWithGameNotReady() {
        List<String> messages = new ArrayList<>();

        Optional<MinecraftPorts.Result<CommandResult, ToolError>> result =
            MinecraftCommandAdapter.checkSingleplayer(
                true, false, true, true, collectingSink(messages));

        assertTrue(result.isPresent());
        assertEquals(ToolErrorCode.GAME_NOT_READY, result.get().error().code());
    }

    @Test
    void disabledCommandsRejectWithCommandRejected() {
        List<String> messages = new ArrayList<>();

        Optional<MinecraftPorts.Result<CommandResult, ToolError>> result =
            MinecraftCommandAdapter.checkSingleplayer(
                true, false, false, false, collectingSink(messages));

        assertTrue(result.isPresent(), "guard must reject when commands are not allowed");
        assertFalse(result.get().isSuccess());
        assertEquals(ToolErrorCode.COMMAND_REJECTED, result.get().error().code());
        assertTrue(messages.get(0).contains("commands are disabled"));
    }

    @Test
    void normalSingleplayerPathProceeds() {
        List<String> messages = new ArrayList<>();

        Optional<MinecraftPorts.Result<CommandResult, ToolError>> result =
            MinecraftCommandAdapter.checkSingleplayer(
                true, false, false, true, collectingSink(messages));

        assertTrue(result.isEmpty(), "guard must allow command submission when not paused and commands allowed");
        assertTrue(messages.isEmpty(), "no rejection message should be sent");
    }

    @Test
    void nonLocalServerSkipsSingleplayerGuards() {
        List<String> messages = new ArrayList<>();

        Optional<MinecraftPorts.Result<CommandResult, ToolError>> result =
            MinecraftCommandAdapter.checkSingleplayer(
                false, true, true, false, collectingSink(messages));

        assertTrue(result.isEmpty(), "multiplayer should bypass the singleplayer guard entirely");
        assertTrue(messages.isEmpty());
    }

    @Test
    void commandsAllowedBooleanLogic() {
        assertFalse(MinecraftCommandAdapter.commandsAllowed(false, false),
            "neither world flag nor player permission");
        assertTrue(MinecraftCommandAdapter.commandsAllowed(true, false),
            "world created with Allow Commands on");
        assertTrue(MinecraftCommandAdapter.commandsAllowed(false, true),
            "LAN operator permission grants command access");
        assertTrue(MinecraftCommandAdapter.commandsAllowed(true, true),
            "both flags true");
    }

    @Test
    void rejectIsSafeWithNullSink() {
        MinecraftPorts.Result<CommandResult, ToolError> result =
            MinecraftCommandAdapter.reject(null, ToolErrorCode.COMMAND_REJECTED, "test reason");

        assertFalse(result.isSuccess());
        assertEquals(ToolErrorCode.COMMAND_REJECTED, result.error().code());
        assertEquals("test reason", result.error().message());
    }
}
