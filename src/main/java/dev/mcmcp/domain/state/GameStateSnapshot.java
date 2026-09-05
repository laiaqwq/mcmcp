package dev.mcmcp.domain.state;

import java.util.List;
import java.util.Map;

/**
 * Immutable game state snapshot. Only requested sections are non-null.
 * See PRD §7.3 for field semantics. All sub-records are immutable.
 */
public record GameStateSnapshot(
    String capturedAt,
    Long gameTick,
    boolean ready,
    ClientSection client,
    ConnectionSection connection,
    PlayerSection player,
    WorldSection world,
    DebugSection debug,
    TargetSection target
) {
    public record ClientSection(
        String minecraftVersion,
        String modVersion,
        Integer fps,
        String screen,
        boolean paused,
        boolean windowFocused,
        Integer guiScale
    ) {}

    public record ConnectionSection(
        boolean connected,
        String type,
        String serverAddress,
        Integer latencyMs
    ) {}

    public record PlayerSection(
        String name,
        String uuid,
        String gameMode,
        Vec3 position,
        Vec3i blockPosition,
        Rotation rotation,
        Vec3 velocity,
        boolean onGround,
        boolean sprinting,
        boolean sneaking,
        boolean swimming,
        boolean fallFlying,
        double health,
        double maxHealth,
        double absorption,
        int food,
        double saturation,
        int air,
        int maxAir,
        int armor,
        int experienceLevel,
        double experienceProgress,
        int selectedHotbarSlot,
        SelectedItem selectedItem,
        List<StatusEffect> statusEffects
    ) {}

    public record WorldSection(
        String dimension,
        String biome,
        String difficulty,
        boolean hardcore,
        Long day,
        Long timeOfDay,
        boolean raining,
        boolean thundering,
        Light light
    ) {}

    public record DebugSection(
        Coordinates coordinates,
        Facing facing,
        ChunkInfo chunk,
        RenderInfo render,
        SystemInfo system
    ) {}

    public record TargetSection(
        String type,
        Double distance,
        BlockTarget block,
        FluidTarget fluid,
        EntityTarget entity
    ) {}

    // Shared sub-records

    public record Vec3(double x, double y, double z) {}
    public record Vec3i(int x, int y, int z) {}
    public record Rotation(double yaw, double pitch) {}
    public record Light(int block, int sky) {}

    public record SelectedItem(
        String id,
        int count,
        String displayName
    ) {}

    public record StatusEffect(
        String id,
        int amplifier,
        Long durationTicks,
        boolean ambient,
        boolean showParticles
    ) {}

    public record Coordinates(
        Vec3 precise,
        Vec3i block,
        Vec3i chunk,
        Vec3i inChunk,
        Vec3i chunkOrigin,
        Region region
    ) {}

    public record Region(int x, int z) {}

    public record Facing(
        String direction,
        String axis,
        String towards,
        double yaw,
        double pitch
    ) {}

    public record ChunkInfo(
        boolean loaded,
        String status,
        Double localDifficulty,
        Long inhabitedTimeTicks,
        Heightmaps heightmaps
    ) {}

    public record Heightmaps(
        Integer worldSurface,
        Integer motionBlocking
    ) {}

    public record RenderInfo(
        Integer renderDistanceChunks,
        Integer simulationDistanceChunks,
        Integer fps,
        Double frameTimeMs,
        Integer chunksRendered,
        Integer entitiesRendered,
        Integer entitiesLoaded,
        Integer particles
    ) {}

    public record SystemInfo(
        String javaVersion,
        Long memoryUsedMiB,
        Long memoryAllocatedMiB,
        Long memoryMaxMiB,
        String cpu,
        String gpu,
        Integer displayWidth,
        Integer displayHeight,
        String graphicsBackend
    ) {}

    public record BlockTarget(
        String id,
        Vec3i position,
        String side,
        Map<String, String> properties,
        List<String> tags
    ) {}

    public record FluidTarget(
        String id,
        Map<String, String> properties,
        List<String> tags
    ) {}

    public record EntityTarget(
        int id,
        String type,
        String displayName,
        String uuid,
        Vec3 position,
        double distance
    ) {}
}
