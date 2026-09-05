package dev.mcmcp.client;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MinecraftStateAdapter}.
 *
 * <p>The snapshot path requires {@code Minecraft.getInstance()} to return a
 * live client, so the section collectors are not reachable in a unit test
 * (see the class-level note on {@link MinecraftClientSchedulerTest}). The one
 * separable pure function — compass facing from yaw — is exercised via
 * reflection to avoid changing production visibility.
 */
class MinecraftStateAdapterTest {

    private static String facing(float yaw) throws Exception {
        Method m = MinecraftStateAdapter.class.getDeclaredMethod("facingDirection", float.class);
        m.setAccessible(true);
        return (String) m.invoke(null, yaw);
    }

    @Test
    void facingSouthInSouthSector() throws Exception {
        assertEquals("south", facing(0f));
        assertEquals("south", facing(44.9f));
        assertEquals("south", facing(-44.9f));
    }

    @Test
    void facingWestInWestSector() throws Exception {
        assertEquals("west", facing(45f));
        assertEquals("west", facing(90f));
        assertEquals("west", facing(134.9f));
    }

    @Test
    void facingEastInEastSector() throws Exception {
        assertEquals("east", facing(-45.1f));
        assertEquals("east", facing(-90f));
        assertEquals("east", facing(-135f)); // -135 satisfies [-135,-45) -> east
    }

    @Test
    void facingNorthInNorthSector() throws Exception {
        assertEquals("north", facing(135f));
        assertEquals("north", facing(180f));
        assertEquals("north", facing(-135.1f));
        assertEquals("north", facing(-179.9f));
    }

    @Test
    void yawIsNormalizedBeforeClassification() throws Exception {
        assertEquals("south", facing(360f));
        assertEquals("south", facing(-360f));
        assertEquals("west", facing(450f));   // 450 - 360 = 90
        assertEquals("east", facing(-450f));  // -450 + 360 = -90
        assertEquals("north", facing(540f));  // 540 - 360 = 180
    }

    @Test
    void boundaryBetweenSectorsIsInclusiveOfLowerEdge() throws Exception {
        // [-45,45)=south, [45,135)=west, [-135,-45)=east, else north
        assertEquals("west", facing(45f));
        assertEquals("south", facing(-45f)); // lower bound is inclusive: -45 is south
        assertEquals("north", facing(135f));
        assertEquals("east", facing(-135f));
    }
}
