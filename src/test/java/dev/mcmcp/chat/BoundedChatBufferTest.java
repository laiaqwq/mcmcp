package dev.mcmcp.chat;

import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatQueryResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoundedChatBufferTest {

    @Test
    void appendAndQuery() {
        var buffer = new BoundedChatBuffer(100);
        buffer.append("s1", ChatMessage.MessageType.SYSTEM, "hello", "2026-01-01T00:00:00Z", 1L);
        buffer.append("s1", ChatMessage.MessageType.SYSTEM, "world", "2026-01-01T00:00:01Z", 2L);

        ChatQueryResult result = buffer.query(null, 50, null);
        assertEquals(2, result.messages().size());
        assertEquals(1, result.messages().get(0).id());
        assertEquals(2, result.messages().get(1).id());
        assertEquals(1L, result.oldestAvailableId());
        assertEquals(2L, result.newestAvailableId());
        assertEquals(2L, result.nextAfterId());
        assertFalse(result.hasMore());
        assertFalse(result.gapDetected());
        assertEquals(0, result.evictedMessageCount());
    }

    @Test
    void evictionUpdatesCounters() {
        var buffer = new BoundedChatBuffer(100);
        for (int i = 0; i < 105; i++) {
            buffer.append("s1", ChatMessage.MessageType.SYSTEM, "msg" + i, "2026-01-01T00:00:00Z", (long) i);
        }
        // 105 messages, capacity 100 → 5 evicted
        ChatQueryResult result = buffer.query(null, 50, null);
        assertEquals(5, result.evictedMessageCount());
        assertEquals(6L, result.oldestAvailableId()); // first 5 evicted (ids 1-5)
        assertEquals(105L, result.newestAvailableId());
    }

    @Test
    void queryWithAfterId() {
        var buffer = new BoundedChatBuffer(100);
        for (int i = 0; i < 10; i++) {
            buffer.append("s1", ChatMessage.MessageType.SYSTEM, "msg" + i, "2026-01-01T00:00:00Z", (long) i);
        }
        // Get earliest 3 with id > 5
        ChatQueryResult result = buffer.query(5L, 3, null);
        assertEquals(3, result.messages().size());
        assertEquals(6, result.messages().get(0).id());
        assertEquals(7, result.messages().get(1).id());
        assertEquals(8, result.messages().get(2).id());
        assertTrue(result.hasMore()); // id 9 and 10 still available
        assertEquals(8L, result.nextAfterId());
    }

    @Test
    void typeFilter() {
        var buffer = new BoundedChatBuffer(100);
        buffer.append("s1", ChatMessage.MessageType.CHAT, "chat1", "t", 1L);
        buffer.append("s1", ChatMessage.MessageType.SYSTEM, "sys1", "t", 2L);
        buffer.append("s1", ChatMessage.MessageType.CHAT, "chat2", "t", 3L);

        ChatQueryResult result = buffer.query(null, 50, List.of(ChatMessage.MessageType.CHAT));
        assertEquals(2, result.messages().size());
        assertEquals("chat1", result.messages().get(0).plainText());
        assertEquals("chat2", result.messages().get(1).plainText());
    }

    @Test
    void emptyBuffer() {
        var buffer = new BoundedChatBuffer(100);
        ChatQueryResult result = buffer.query(null, 50, null);
        assertTrue(result.messages().isEmpty());
        assertNull(result.oldestAvailableId());
        assertNull(result.newestAvailableId());
        assertNull(result.nextAfterId());
    }

    @Test
    void emptyBufferWithAfterId() {
        var buffer = new BoundedChatBuffer(100);
        ChatQueryResult result = buffer.query(42L, 50, null);
        assertTrue(result.messages().isEmpty());
        assertEquals(42L, result.nextAfterId());
    }

    @Test
    void gapDetectedAfterEviction() {
        var buffer = new BoundedChatBuffer(100);
        for (int i = 0; i < 105; i++) {
            buffer.append("s1", ChatMessage.MessageType.SYSTEM, "msg" + i, "t", (long) i);
        }
        // after_id=3, but ids 1-5 were evicted → gap
        ChatQueryResult result = buffer.query(3L, 50, null);
        assertTrue(result.gapDetected());
    }

    @Test
    void sanitizesIsolatedSurrogates() {
        String sanitized = BoundedChatBuffer.sanitizeText("hello\ud800world");
        assertEquals("hello\uFFFDworld", sanitized);
    }
}
