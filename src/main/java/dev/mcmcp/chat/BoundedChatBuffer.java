package dev.mcmcp.chat;

import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatMessage.MessageType;
import dev.mcmcp.domain.chat.ChatQueryResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * Bounded ring buffer for chat messages. Written from the client event thread,
 * read from protocol executor threads. Uses a StampedLock for fast optimistic reads.
 *
 * <p>Semantics per PRD §7.2, §9 and IMPLEMENTATION.md §11.2:
 * <ul>
 *   <li>IDs are process-level monotonic from 1, never reset on world switch.</li>
 *   <li>Capacity is fixed at startup (100–10000).</li>
 *   <li>Eviction updates oldestId, lastEvictedId, evictedCount.</li>
 *   <li>No de-duplication; no Component references stored.</li>
 * </ul>
 */
public final class BoundedChatBuffer {

    private final ChatMessage[] ring;
    private final int capacity;
    private final StampedLock lock = new StampedLock();
    private final AtomicLong idGen = new AtomicLong(0);

    private long head = 0;       // index of next write slot
    private long count = 0;      // number of stored messages
    private long oldestId = 0;   // id of oldest stored message (0 if empty)
    private long newestId = 0;   // id of newest stored message (0 if empty)
    private long lastEvictedId = 0;
    private long evictedCount = 0;

    public BoundedChatBuffer(int capacity) {
        if (capacity < 100 || capacity > 10000)
            throw new IllegalArgumentException("capacity must be 100–10000, got " + capacity);
        this.capacity = capacity;
        this.ring = new ChatMessage[capacity];
    }

    /**
     * Append a message. Called from the client event thread.
     */
    public void append(String sessionId, MessageType type, String plainText, String receivedAt, Long gameTick) {
        long id = idGen.incrementAndGet();
        var msg = new ChatMessage(id, sessionId, type, sanitizeText(plainText), receivedAt, gameTick);
        long stamp = lock.writeLock();
        try {
            int idx = (int) (head % capacity);
            ChatMessage evicted = ring[idx];
            if (evicted != null) {
                lastEvictedId = evicted.id();
                evictedCount++;
            }
            ring[idx] = msg;
            head++;
            if (count < capacity) {
                count++;
            }
            oldestId = ring[(int) ((head - count) % capacity)].id();
            newestId = id;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Query messages. Called from protocol executor threads.
     *
     * @param afterId null = most recent limit; non-null = earliest limit with id > afterId
     * @param limit 1–200
     * @param types null or empty = all
     */
    public ChatQueryResult query(Long afterId, int limit, List<MessageType> types) {
        long stamp = lock.readLock();
        try {
            if (count == 0) {
                return new ChatQueryResult(
                    List.of(), null, null,
                    afterId != null ? afterId : null,
                    false, false, evictedCount
                );
            }

            // Collect all stored messages in ascending id order
            List<ChatMessage> all = new ArrayList<>((int) Math.min(count, capacity));
            long start = head - count;
            for (long i = 0; i < count; i++) {
                int idx = (int) ((start + i) % capacity);
                all.add(ring[idx]);
            }

            // Filter by type
            List<ChatMessage> filtered = new ArrayList<>();
            for (var m : all) {
                if (types == null || types.isEmpty() || types.contains(m.type())) {
                    filtered.add(m);
                }
            }

            List<ChatMessage> result;
            boolean hasMore = false;

            if (afterId == null) {
                // Most recent `limit` messages, then ascending
                int from = Math.max(0, filtered.size() - limit);
                result = new ArrayList<>(filtered.subList(from, filtered.size()));
                hasMore = false; // PRD: has_more is false when no after_id
            } else {
                // Earliest `limit` messages with id > afterId
                List<ChatMessage> after = new ArrayList<>();
                for (var m : filtered) {
                    if (m.id() > afterId) {
                        after.add(m);
                        if (after.size() > limit) break;
                    }
                }
                result = new ArrayList<>(after.size() <= limit ? after : after.subList(0, limit));
                // Check if there are more messages beyond the returned ones
                if (result.size() == limit) {
                    long lastReturnedId = result.get(result.size() - 1).id();
                    for (var m : filtered) {
                        if (m.id() > lastReturnedId) {
                            hasMore = true;
                            break;
                        }
                    }
                }
            }

            long nextAfterId;
            if (result.isEmpty()) {
                nextAfterId = afterId != null ? afterId : 0L;
                if (afterId == null) nextAfterId = 0L; // will be null in DTO
            } else {
                nextAfterId = result.get(result.size() - 1).id();
            }

            boolean gapDetected = afterId != null && lastEvictedId > afterId;

            return new ChatQueryResult(
                result,
                oldestId,
                newestId,
                nextAfterId == 0 ? null : nextAfterId,
                hasMore,
                gapDetected,
                evictedCount
            );
        } finally {
            lock.unlockRead(stamp);
        }
    }

    public long evictedCount() {
        long stamp = lock.tryOptimisticRead();
        long ev = evictedCount;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                ev = evictedCount;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return ev;
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Replace isolated surrogate code points with U+FFFD.
     */
    static String sanitizeText(String s) {
        if (s == null) return "";
        var sb = new StringBuilder(s.length());
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                } else {
                    sb.append('\uFFFD');
                }
            } else if (Character.isLowSurrogate(c)) {
                sb.append('\uFFFD');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
