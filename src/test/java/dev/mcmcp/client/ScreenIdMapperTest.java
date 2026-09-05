package dev.mcmcp.client;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScreenIdMapper} mapping rules (PRD §7.3).
 *
 * <p>{@code Screen} cannot be instantiated in a unit test — its constructor
 * reads {@code Minecraft.getInstance().font}, which NPEs without a running
 * client, and some concrete subclasses (e.g. CreativeModeInventoryScreen)
 * trigger registry bootstrap on class init. The tests therefore cover:
 * <ul>
 *   <li>null handling,</li>
 *   <li>the "class:&lt;fqn&gt;" fallback via an uninitialized Screen subclass
 *       allocated with {@code Unsafe} (map() only calls getClass()),</li>
 *   <li>the full known-screen table contents via reflection on the private
 *       static map, loading classes without initialization.</li>
 * </ul>
 */
class ScreenIdMapperTest {

    @Test
    void nullScreenMapsToNull() {
        assertNull(ScreenIdMapper.map(null));
    }

    @Test
    void unknownScreenClassGetsClassPrefixedFqn() throws Exception {
        Screen unknown = allocateScreen(CustomScreen.class);
        assertEquals("class:" + CustomScreen.class.getName(), ScreenIdMapper.map(unknown));
    }

    @Test
    void unknownScreenFallbackIsDistinctPerClass() throws Exception {
        Screen a = allocateScreen(CustomScreen.class);
        Screen b = allocateScreen(OtherScreen.class);
        String idA = ScreenIdMapper.map(a);
        String idB = ScreenIdMapper.map(b);
        assertNotEquals(idA, idB);
        assertTrue(idA.startsWith("class:"));
        assertTrue(idB.startsWith("class:"));
        assertTrue(idA.endsWith("CustomScreen"));
        assertTrue(idB.endsWith("OtherScreen"));
    }

    /**
     * Instantiates a Screen subclass without running its constructor (which
     * requires a live Minecraft instance for {@code font}). map() only calls
     * {@code getClass()}, so an uninitialized instance is safe here.
     */
    private static Screen allocateScreen(Class<? extends Screen> type) throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (Screen) unsafe.allocateInstance(type);
    }

    private static class CustomScreen extends Screen {
        CustomScreen() { super(Component.literal("custom")); }
    }

    private static class OtherScreen extends Screen {
        OtherScreen() { super(Component.literal("other")); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void knownScreenClassesAreRegistered() throws Exception {
        Field field = ScreenIdMapper.class.getDeclaredField("MAP");
        field.setAccessible(true);
        var map = (Map<Class<?>, String>) field.get(null);
        var loader = ScreenIdMapper.class.getClassLoader();

        // Load without <clinit>: several screen classes trigger Minecraft
        // registry bootstrap on initialization, which fails headless.
        java.util.function.Function<String, Class<?>> load = name -> {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                throw new AssertionError("missing screen class: " + name, e);
            }
        };

        assertEquals("minecraft:title",
            map.get(load.apply("net.minecraft.client.gui.screens.TitleScreen")));
        assertEquals("minecraft:pause",
            map.get(load.apply("net.minecraft.client.gui.screens.PauseScreen")));
        assertEquals("minecraft:chat",
            map.get(load.apply("net.minecraft.client.gui.screens.ChatScreen")));
        assertEquals("minecraft:inventory",
            map.get(load.apply("net.minecraft.client.gui.screens.inventory.InventoryScreen")));
        assertEquals("minecraft:creative_inventory",
            map.get(load.apply("net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen")));
        assertEquals("minecraft:death",
            map.get(load.apply("net.minecraft.client.gui.screens.DeathScreen")));
        assertEquals("minecraft:advancements",
            map.get(load.apply("net.minecraft.client.gui.screens.advancements.AdvancementsScreen")));
        assertEquals("minecraft:options",
            map.get(load.apply("net.minecraft.client.gui.screens.options.OptionsScreen")));
        assertEquals("minecraft:multiplayer",
            map.get(load.apply("net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen")));
        assertEquals("minecraft:disconnected",
            map.get(load.apply("net.minecraft.client.gui.screens.DisconnectedScreen")));
        assertEquals("minecraft:container",
            map.get(load.apply("net.minecraft.client.gui.screens.inventory.ContainerScreen")));
        assertEquals(11, map.size(),
            "mapping table should contain exactly the 11 known screens");
    }
}
