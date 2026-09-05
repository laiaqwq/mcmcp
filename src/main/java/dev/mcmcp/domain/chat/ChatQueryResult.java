package dev.mcmcp.domain.chat;

import java.util.List;

/**
 * Immutable result of a chat query. See PRD §7.2.
 */
public record ChatQueryResult(
    List<ChatMessage> messages,
    Long oldestAvailableId,
    Long newestAvailableId,
    Long nextAfterId,
    boolean hasMore,
    boolean gapDetected,
    long evictedMessageCount
) {}
