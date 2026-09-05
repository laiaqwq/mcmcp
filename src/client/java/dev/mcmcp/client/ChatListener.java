package dev.mcmcp.client;

import com.mojang.authlib.GameProfile;
import dev.mcmcp.chat.BoundedChatBuffer;
import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.observability.McmcpLogger;
import dev.mcmcp.util.TimeUtil;
import java.time.Instant;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;

/**
 * Chat message listener. Registers with Fabric's ClientReceiveMessageEvents.
 * Maps player chat -> "chat", game/system -> "system" (only if it enters the chat HUD,
 * not action bar). See PRD §7.2, §9 and IMPLEMENTATION.md §11.2.
 *
 * <p>Targets Minecraft 26.2 with Mojang mappings (Component, not Text).
 */
public final class ChatListener {

    private final BoundedChatBuffer buffer;
    private final ClientSessionTracker sessionTracker;

    public ChatListener(BoundedChatBuffer buffer, ClientSessionTracker sessionTracker) {
        this.buffer = buffer;
        this.sessionTracker = sessionTracker;
    }

    public void register() {
        // Player chat messages (MC 26.2 Mojang-mapped signature)
        ClientReceiveMessageEvents.CHAT.register(
            (Component message, PlayerChatMessage signedMessage, GameProfile sender,
             ChatType.Bound boundType, Instant timestamp) ->
                capture(message, ChatMessage.MessageType.CHAT));

        // Game/system messages (includes command feedback and server messages)
        ClientReceiveMessageEvents.GAME.register((Component message, boolean overlay) -> {
            // overlay = action bar; PRD excludes action bar
            if (overlay) return;
            capture(message, ChatMessage.MessageType.SYSTEM);
        });

        McmcpLogger.info("chat_listener_registered");
    }

    private void capture(Component message, ChatMessage.MessageType type) {
        try {
            String plainText = message.getString();
            String sessionId = sessionTracker.currentSessionId();
            if (sessionId == null) sessionId = "no-session";

            Long gameTick = null;
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                gameTick = mc.level.getLevelData().getGameTime();
            }

            buffer.append(sessionId, type, plainText, TimeUtil.nowUtc(), gameTick);
        } catch (Exception e) {
            McmcpLogger.error("chat_capture_error", "error", e.getMessage());
        }
    }
}
