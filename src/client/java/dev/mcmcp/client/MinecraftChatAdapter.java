package dev.mcmcp.client;

import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.chat.BoundedChatBuffer;
import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatQueryResult;
import java.util.List;

/**
 * Chat port implementation. Delegates to the BoundedChatBuffer.
 * Thread-safe: the buffer uses a StampedLock.
 */
public final class MinecraftChatAdapter implements MinecraftPorts.ChatPort {

    private final BoundedChatBuffer buffer;

    public MinecraftChatAdapter(BoundedChatBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public ChatQueryResult query(Long afterId, int limit, List<ChatMessage.MessageType> types) {
        return buffer.query(afterId, limit, types);
    }
}
