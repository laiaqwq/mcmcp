package dev.mcmcp.client;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Tracks the current client session and lifecycle generation.
 * See IMPLEMENTATION.md §5.2.
 *
 * <p>lifecycleGeneration increments on join, disconnect, level/player replacement, and shutdown.
 * sessionId is a UUID v4 generated on each new play connection.
 *
 * <p>Uses {@link Minecraft#getInstance()} to verify that the player, level, and connection
 * are currently available (Minecraft 26.2 Mojang mappings).
 */
public final class ClientSessionTracker {

    private final AtomicLong lifecycleGeneration = new AtomicLong(0);
    private volatile String sessionId = null;

    /**
     * Called when entering a new play connection.
     */
    public void onJoin() {
        lifecycleGeneration.incrementAndGet();
        sessionId = UUID.randomUUID().toString();
    }

    /**
     * Called on disconnect, level switch, or player replacement.
     */
    public void onDisconnect() {
        lifecycleGeneration.incrementAndGet();
    }

    public long currentGeneration() {
        return lifecycleGeneration.get();
    }

    public String currentSessionId() {
        return sessionId;
    }

    /**
     * Whether a session is currently active (player has joined a level).
     */
    public boolean hasSession() {
        return sessionId != null;
    }

    /**
     * Whether the client is currently in a playable state with a live connection.
     *
     * <p>Checks {@link Minecraft#getInstance()} and verifies that the player,
     * level, and connection are all non-null.
     */
    public boolean isClientReady() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        ClientPacketListener connection = client.getConnection();
        return player != null && level != null && connection != null;
    }
}
