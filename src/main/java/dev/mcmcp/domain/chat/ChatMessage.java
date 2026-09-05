package dev.mcmcp.domain.chat;

/**
 * Immutable chat message DTO. Copied at capture time from the client thread;
 * never holds a reference to a Minecraft Component.
 */
public record ChatMessage(
    long id,
    String sessionId,
    MessageType type,
    String plainText,
    String receivedAt,
    Long gameTick
) {
    public enum MessageType {
        CHAT("chat"),
        SYSTEM("system");

        private final String wireName;

        MessageType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static MessageType fromWire(String name) {
            return switch (name) {
                case "chat" -> CHAT;
                case "system" -> SYSTEM;
                default -> throw new IllegalArgumentException("Unknown message type: " + name);
            };
        }
    }
}
