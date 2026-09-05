package dev.mcmcp.client;

import dev.mcmcp.chat.BoundedChatBuffer;
import dev.mcmcp.domain.chat.ChatMessage.MessageType;
import java.time.Instant;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ChatListener}'s event matrix (PRD §7.2, §9).
 *
 * <p>Fabric's {@code ClientReceiveMessageEvents} are plain
 * {@code EventFactory}-backed multicasters — no game instance is needed to
 * register handlers or fire the invoker, so the full capture matrix is
 * testable headless. The {@code gameTick} field is null here because
 * {@code Minecraft.getInstance()} has no level without a running client.
 */
class ChatListenerTest {

    private record Harness(BoundedChatBuffer buffer, ClientSessionTracker tracker) {}

    private Harness register() {
        var buffer = new BoundedChatBuffer(100);
        var tracker = new ClientSessionTracker();
        new ChatListener(buffer, tracker).register();
        return new Harness(buffer, tracker);
    }

    @Test
    void gameMessageIsCapturedAsSystem() {
        var h = register();
        ClientReceiveMessageEvents.GAME.invoker()
            .onReceiveGameMessage(Component.literal("server says hi"), false);

        var result = h.buffer().query(null, 10, null);
        assertEquals(1, result.messages().size());
        var msg = result.messages().get(0);
        assertEquals(MessageType.SYSTEM, msg.type());
        assertEquals("server says hi", msg.plainText());
        assertNotNull(msg.receivedAt());
        assertNull(msg.gameTick(), "no level without a running client");
    }

    @Test
    void actionBarOverlayMessageIsExcluded() {
        // PRD: action bar (overlay=true) must NOT enter the chat buffer.
        var h = register();
        ClientReceiveMessageEvents.GAME.invoker()
            .onReceiveGameMessage(Component.literal("action bar"), true);

        assertTrue(h.buffer().query(null, 10, null).messages().isEmpty());
    }

    @Test
    void playerChatIsCapturedAsChatType() {
        var h = register();
        ClientReceiveMessageEvents.CHAT.invoker()
            .onReceiveChatMessage(Component.literal("<steve> yo"), null, null, null,
                Instant.parse("2026-01-01T00:00:00Z"));

        var result = h.buffer().query(null, 10, null);
        assertEquals(1, result.messages().size());
        var msg = result.messages().get(0);
        assertEquals(MessageType.CHAT, msg.type());
        assertEquals("<steve> yo", msg.plainText());
    }

    @Test
    void messageWithoutSessionGetsNoSessionMarker() {
        var h = register();
        ClientReceiveMessageEvents.GAME.invoker()
            .onReceiveGameMessage(Component.literal("pre-join"), false);

        var msg = h.buffer().query(null, 10, null).messages().get(0);
        assertEquals("no-session", msg.sessionId());
    }

    @Test
    void messageDuringSessionCarriesSessionId() {
        var h = register();
        h.tracker().onJoin();
        String sessionId = h.tracker().currentSessionId();

        ClientReceiveMessageEvents.GAME.invoker()
            .onReceiveGameMessage(Component.literal("in game"), false);

        var msg = h.buffer().query(null, 10, null).messages().get(0);
        assertEquals(sessionId, msg.sessionId());
    }

    @Test
    void eventsFromOtherListenersAreNotDoubleCaptured() {
        // Each ChatListener registers its own handlers on the shared events;
        // this test only installs one listener and asserts single capture.
        var h = register();
        ClientReceiveMessageEvents.GAME.invoker()
            .onReceiveGameMessage(Component.literal("once"), false);
        assertEquals(1, h.buffer().query(null, 10, null).messages().size());
    }
}
