package dev.mcmcp.client;

import dev.mcmcp.chat.BoundedChatBuffer;
import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatMessage.MessageType;
import dev.mcmcp.domain.chat.ChatQueryResult;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MinecraftChatAdapter}, which is a thin delegation seam
 * over {@link BoundedChatBuffer}. Fully testable headless: no Minecraft
 * types are touched.
 */
class MinecraftChatAdapterTest {

    @Test
    void emptyBufferReturnsEmptyResult() {
        var adapter = new MinecraftChatAdapter(new BoundedChatBuffer(100));
        ChatQueryResult result = adapter.query(null, 10, null);
        assertNotNull(result);
        assertTrue(result.messages().isEmpty());
        assertFalse(result.hasMore());
    }

    @Test
    void queryReturnsAppendedMessages() {
        var buffer = new BoundedChatBuffer(100);
        buffer.append("s1", MessageType.CHAT, "hello", "2026-01-01T00:00:00Z", 42L);
        buffer.append("s1", MessageType.SYSTEM, "world", "2026-01-01T00:00:01Z", 43L);

        var adapter = new MinecraftChatAdapter(buffer);
        ChatQueryResult result = adapter.query(null, 10, null);

        assertEquals(2, result.messages().size());
        assertEquals("hello", result.messages().get(0).plainText());
        assertEquals("world", result.messages().get(1).plainText());
        assertEquals(1L, result.oldestAvailableId());
        assertEquals(2L, result.newestAvailableId());
    }

    @Test
    void queryPassesThroughAfterIdLimitAndTypeFilter() {
        var buffer = new BoundedChatBuffer(100);
        buffer.append("s1", MessageType.CHAT, "m1", "t", null);
        buffer.append("s1", MessageType.SYSTEM, "m2", "t", null);
        buffer.append("s1", MessageType.CHAT, "m3", "t", null);

        var adapter = new MinecraftChatAdapter(buffer);

        ChatQueryResult chatOnly = adapter.query(null, 10, List.of(MessageType.CHAT));
        assertEquals(2, chatOnly.messages().size());
        assertTrue(chatOnly.messages().stream()
            .allMatch(m -> m.type() == MessageType.CHAT));

        ChatQueryResult afterFirst = adapter.query(1L, 10, null);
        assertEquals(2, afterFirst.messages().size());
        assertEquals(2L, afterFirst.messages().get(0).id());

        // PRD: has_more is only computed for after_id queries; with
        // afterId=null the "most recent N" page always reports hasMore=false.
        ChatQueryResult page = adapter.query(null, 1, null);
        assertEquals(1, page.messages().size());
        assertFalse(page.hasMore());

        ChatQueryResult paged = adapter.query(0L, 1, null);
        assertEquals(1, paged.messages().size());
        assertTrue(paged.hasMore());
    }

    @Test
    void queryReturnsSameResultAsDirectBufferCall() {
        var buffer = new BoundedChatBuffer(100);
        buffer.append("s", MessageType.CHAT, "x", "t", 1L);
        var adapter = new MinecraftChatAdapter(buffer);

        ChatQueryResult viaAdapter = adapter.query(null, 5, List.of(MessageType.CHAT));
        ChatQueryResult viaBuffer = buffer.query(null, 5, List.of(MessageType.CHAT));
        assertEquals(viaBuffer, viaAdapter);
    }
}
