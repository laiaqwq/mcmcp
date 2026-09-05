package dev.mcmcp.client;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

/**
 * Maps GUI Screen instances to stable identifiers per PRD §7.3.
 * Uses explicit instanceof table; unknown classes get "class:<fqn>".
 */
public final class ScreenIdMapper {

    private static final Map<Class<?>, String> MAP = new LinkedHashMap<>();

    static {
        MAP.put(TitleScreen.class, "minecraft:title");
        MAP.put(PauseScreen.class, "minecraft:pause");
        MAP.put(ChatScreen.class, "minecraft:chat");
        MAP.put(InventoryScreen.class, "minecraft:inventory");
        MAP.put(CreativeModeInventoryScreen.class, "minecraft:creative_inventory");
        MAP.put(DeathScreen.class, "minecraft:death");
        MAP.put(AdvancementsScreen.class, "minecraft:advancements");
        MAP.put(OptionsScreen.class, "minecraft:options");
        MAP.put(JoinMultiplayerScreen.class, "minecraft:multiplayer");
        MAP.put(DisconnectedScreen.class, "minecraft:disconnected");
        MAP.put(ContainerScreen.class, "minecraft:container");
    }

    public static String map(Screen screen) {
        if (screen == null) return null;
        String id = MAP.get(screen.getClass());
        if (id != null) return id;
        return "class:" + screen.getClass().getName();
    }
}
