package dev.mcmcp.client;

import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.domain.state.GameStateSnapshot;
import dev.mcmcp.observability.McmcpLogger;
import dev.mcmcp.util.CoordMath;
import dev.mcmcp.util.TimeUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * State port implementation. Collects a structured game state snapshot on the client thread.
 * See PRD §7.3 and IMPLEMENTATION.md §11.3.
 */
public final class MinecraftStateAdapter implements MinecraftPorts.StatePort {

    private final MinecraftClientScheduler scheduler;
    private final ClientSessionTracker sessionTracker;
    private final String modVersion;

    public MinecraftStateAdapter(MinecraftClientScheduler scheduler,
                                  ClientSessionTracker sessionTracker, String modVersion) {
        this.scheduler = scheduler;
        this.sessionTracker = sessionTracker;
        this.modVersion = modVersion;
    }

    @Override
    public CompletableFuture<MinecraftPorts.Result<GameStateSnapshot, ToolError>> snapshot(
        List<String> sections, long deadlineNanos
    ) {
        return scheduler.submit(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return MinecraftPorts.Result.err(ToolError.of(ToolErrorCode.GAME_NOT_READY,
                    "minecraft client not available"));
            }

            long generation = sessionTracker.currentGeneration();
            ClientLevel level = mc.level;
            LocalPlayer player = mc.player;

            boolean ready = level != null && player != null;
            String capturedAt = TimeUtil.nowUtc();
            Long gameTick = (level != null) ? level.getLevelData().getGameTime() : null;

            GameStateSnapshot.ClientSection clientSection = null;
            GameStateSnapshot.ConnectionSection connectionSection = null;
            GameStateSnapshot.PlayerSection playerSection = null;
            GameStateSnapshot.WorldSection worldSection = null;
            GameStateSnapshot.DebugSection debugSection = null;
            GameStateSnapshot.TargetSection targetSection = null;

            if (sections.contains("client")) {
                clientSection = collectClient(mc);
            }
            if (sections.contains("connection")) {
                connectionSection = collectConnection(mc);
            }

            if (ready) {
                ClientLevel levelRef = level;
                LocalPlayer playerRef = player;

                if (sections.contains("player")) {
                    playerSection = collectPlayer(mc, playerRef);
                }
                if (sections.contains("world")) {
                    worldSection = collectWorld(mc, levelRef, playerRef);
                }
                if (sections.contains("debug")) {
                    debugSection = collectDebug(mc, levelRef, playerRef);
                }
                if (sections.contains("target")) {
                    targetSection = collectTarget(mc, levelRef, playerRef);
                }

                if (mc.level != levelRef || mc.player != playerRef
                    || sessionTracker.currentGeneration() != generation) {
                    McmcpLogger.debug("state_consistency_failed");
                    return MinecraftPorts.Result.ok(new GameStateSnapshot(
                        capturedAt, null, false, clientSection, connectionSection,
                        null, null, null, null));
                }
            }

            return MinecraftPorts.Result.ok(new GameStateSnapshot(
                capturedAt, gameTick, ready, clientSection, connectionSection,
                playerSection, worldSection, debugSection, targetSection));
        }, deadlineNanos).handle((result, throwable) -> {
            MinecraftPorts.Result<GameStateSnapshot, ToolError> captured =
                (MinecraftPorts.Result<GameStateSnapshot, ToolError>) result;
            if (throwable != null) {
                if (throwable instanceof TimeoutException) {
                    return MinecraftPorts.Result.<GameStateSnapshot, ToolError>err(ToolError.of(ToolErrorCode.TIMEOUT,
                        "state collection timed out"));
                }
                return MinecraftPorts.Result.<GameStateSnapshot, ToolError>err(ToolError.internal("internal error"));
            }
            return captured;
        });
    }

    private GameStateSnapshot.ClientSection collectClient(Minecraft mc) {
        Integer fps = mc.getFps() > 0 ? mc.getFps() : null;
        Screen screen = mc.gui.screen();
        String screenId = ScreenIdMapper.map(screen);
        boolean paused = mc.isPaused();
        boolean focused = true; // MC 26.2 doesn't expose isWindowFocused via stable API
        Integer guiScale = mc.options.guiScale().get();
        return new GameStateSnapshot.ClientSection(
            mc.getLaunchedVersion(), modVersion, fps, screenId, paused, focused, guiScale
        );
    }

    private GameStateSnapshot.ConnectionSection collectConnection(Minecraft mc) {
        boolean connected = mc.level != null && mc.player != null;
        String type = null;
        String serverAddress = null;
        Integer latency = null;

        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            if (mc.isLocalServer()) {
                type = "singleplayer";
            } else {
                type = "multiplayer";
                ServerData sd = mc.getCurrentServer();
                if (sd != null) serverAddress = sd.ip;
            }
            if (mc.player != null) {
                PlayerInfo info = conn.getPlayerInfo(mc.player.getUUID());
                if (info != null) latency = info.getLatency();
            }
        } else {
            type = "none";
        }

        return new GameStateSnapshot.ConnectionSection(connected, type, serverAddress, latency);
    }

    private GameStateSnapshot.PlayerSection collectPlayer(Minecraft mc, LocalPlayer player) {
        Vec3 pos = player.getPosition(0f);
        GameStateSnapshot.Vec3 position = new GameStateSnapshot.Vec3(pos.x, pos.y, pos.z);
        int blockX = CoordMath.floorToBlock(pos.x);
        int blockY = CoordMath.floorToBlock(pos.y);
        int blockZ = CoordMath.floorToBlock(pos.z);
        GameStateSnapshot.Vec3i blockPosition = new GameStateSnapshot.Vec3i(blockX, blockY, blockZ);

        GameStateSnapshot.Rotation rotation = new GameStateSnapshot.Rotation(player.getYRot(), player.getXRot());
        Vec3 vel = player.getDeltaMovement();
        GameStateSnapshot.Vec3 velocity = new GameStateSnapshot.Vec3(vel.x, vel.y, vel.z);

        GameStateSnapshot.SelectedItem selectedItem = null;
        ItemStack heldStack = player.getMainHandItem();
        if (!heldStack.isEmpty()) {
            selectedItem = new GameStateSnapshot.SelectedItem(
                heldStack.getItem().toString(),
                heldStack.getCount(),
                heldStack.getDisplayName().getString()
            );
        }

        List<GameStateSnapshot.StatusEffect> effects = new ArrayList<>();
        for (MobEffectInstance e : player.getActiveEffects()) {
            String id = e.getEffect().unwrapKey()
                .map(k -> k.identifier().toString())
                .orElse(e.getEffect().value().toString());
            Long duration = e.isInfiniteDuration() ? null : (long) e.getDuration();
            effects.add(new GameStateSnapshot.StatusEffect(
                id, e.getAmplifier(), duration, e.isAmbient(), e.isVisible()
            ));
        }
        effects.sort((a, b) -> a.id().compareTo(b.id()));

        FoodData food = player.getFoodData();
        MultiPlayerGameMode gameMode = mc.gameMode;
        String gameModeName = gameMode != null ? gameMode.getPlayerMode().getName() : null;

        return new GameStateSnapshot.PlayerSection(
            player.getName().getString(),
            player.getUUID().toString(),
            gameModeName,
            position, blockPosition, rotation, velocity,
            player.onGround(), player.isSprinting(), player.isCrouching(),
            player.isSwimming(), player.isFallFlying(),
            player.getHealth(), player.getMaxHealth(), player.getAbsorptionAmount(),
            food.getFoodLevel(),
            food.getSaturationLevel(),
            player.getAirSupply(), player.getMaxAirSupply(),
            player.getArmorValue(),
            player.experienceLevel, player.experienceProgress,
            player.getInventory().getSelectedSlot(),
            selectedItem, effects
        );
    }

    private GameStateSnapshot.WorldSection collectWorld(Minecraft mc, ClientLevel level, LocalPlayer player) {
        ResourceKey<Level> dimKey = level.dimension();
        String dimension = dimKey.identifier().toString();
        String biome = null;
        LevelData levelData = level.getLevelData();
        String difficulty = levelData.getDifficulty().getSerializedName();
        boolean hardcore = levelData.isHardcore();
        long gameTime = levelData.getGameTime();
        Long day = gameTime / 24000L;
        Long timeOfDay = gameTime % 24000L;
        boolean raining = level.isRaining();
        boolean thundering = level.isThundering();

        Holder<Biome> biomeEntry = level.getBiome(player.blockPosition());
        if (biomeEntry.unwrapKey().isPresent()) {
            biome = biomeEntry.unwrapKey().get().identifier().toString();
        } else {
            biome = biomeEntry.value().toString();
        }

        BlockPos bp = player.blockPosition();
        int blockLight = level.getMaxLocalRawBrightness(bp);
        int skyLight = level.getSkyDarken();

        return new GameStateSnapshot.WorldSection(
            dimension, biome, difficulty, hardcore, day, timeOfDay,
            raining, thundering, new GameStateSnapshot.Light(blockLight, skyLight)
        );
    }

    private GameStateSnapshot.DebugSection collectDebug(Minecraft mc, ClientLevel level, LocalPlayer player) {
        Vec3 pos = player.getPosition(0f);
        int blockX = CoordMath.floorToBlock(pos.x);
        int blockY = CoordMath.floorToBlock(pos.y);
        int blockZ = CoordMath.floorToBlock(pos.z);
        int chunkX = CoordMath.blockToChunk(blockX);
        int chunkY = CoordMath.blockToChunk(blockY);
        int chunkZ = CoordMath.blockToChunk(blockZ);
        int inChunkX = CoordMath.blockToInChunk(blockX);
        int inChunkY = CoordMath.blockToInChunk(blockY);
        int inChunkZ = CoordMath.blockToInChunk(blockZ);
        int originX = CoordMath.chunkOrigin(chunkX);
        int originY = CoordMath.chunkOrigin(chunkY);
        int originZ = CoordMath.chunkOrigin(chunkZ);
        int regionX = CoordMath.chunkToRegion(chunkX);
        int regionZ = CoordMath.chunkToRegion(chunkZ);

        GameStateSnapshot.Coordinates coords = new GameStateSnapshot.Coordinates(
            new GameStateSnapshot.Vec3(pos.x, pos.y, pos.z),
            new GameStateSnapshot.Vec3i(blockX, blockY, blockZ),
            new GameStateSnapshot.Vec3i(chunkX, chunkY, chunkZ),
            new GameStateSnapshot.Vec3i(inChunkX, inChunkY, inChunkZ),
            new GameStateSnapshot.Vec3i(originX, originY, originZ),
            new GameStateSnapshot.Region(regionX, regionZ)
        );

        float yaw = Mth.wrapDegrees(player.getYRot());
        float pitch = player.getXRot();
        String direction = facingDirection(yaw);
        String axis = direction.equals("north") || direction.equals("south") ? "z" : "x";
        String towards = switch (direction) {
            case "north" -> "negative_z";
            case "south" -> "positive_z";
            case "west" -> "negative_x";
            case "east" -> "positive_x";
            default -> "negative_z";
        };
        GameStateSnapshot.Facing facing = new GameStateSnapshot.Facing(direction, axis, towards, yaw, pitch);

        GameStateSnapshot.ChunkInfo chunkInfo = collectChunkInfo(level, blockX, blockY, blockZ);
        GameStateSnapshot.RenderInfo renderInfo = collectRenderInfo(mc);
        GameStateSnapshot.SystemInfo systemInfo = collectSystemInfo(mc);

        return new GameStateSnapshot.DebugSection(coords, facing, chunkInfo, renderInfo, systemInfo);
    }

    private GameStateSnapshot.ChunkInfo collectChunkInfo(ClientLevel level, int x, int y, int z) {
        int chunkX = CoordMath.blockToChunk(x);
        int chunkZ = CoordMath.blockToChunk(z);
        ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return new GameStateSnapshot.ChunkInfo(false, null, null, null, null);
        }

        String status = null;
        Double localDifficulty = null;
        Long inhabitedTime = null;
        GameStateSnapshot.Heightmaps heightmaps = null;

        try {
            status = chunk instanceof LevelChunk ? "full" : chunk.getPersistedStatus().toString();
            BlockPos bp = new BlockPos(x, y, z);
            DifficultyInstance diffInstance = new DifficultyInstance(
                level.getLevelData().getDifficulty(),
                level.getLevelData().getGameTime(),
                0L,
                0.0f
            );
            localDifficulty = (double) diffInstance.getEffectiveDifficulty();
            inhabitedTime = chunk.getInhabitedTime();
            Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
            Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
            Integer ws = worldSurface != null ? worldSurface.getFirstAvailable(x & 15, z & 15) : null;
            Integer mb = motionBlocking != null ? motionBlocking.getFirstAvailable(x & 15, z & 15) : null;
            heightmaps = new GameStateSnapshot.Heightmaps(ws, mb);
        } catch (Exception e) {
            // Fields remain null
        }

        return new GameStateSnapshot.ChunkInfo(true, status, localDifficulty, inhabitedTime, heightmaps);
    }

    private GameStateSnapshot.RenderInfo collectRenderInfo(Minecraft mc) {
        Integer fps = mc.getFps() > 0 ? mc.getFps() : null;
        Double frameTime = fps != null ? 1000.0 / fps : null;
        Integer renderDistance = mc.options.renderDistance().get();
        Integer simDistance = mc.options.simulationDistance().get();
        Integer chunksRendered = null;
        Integer entitiesRendered = null;
        Integer entitiesLoaded = null;
        Integer particles = null;

        try {
            if (mc.level != null) {
                entitiesLoaded = mc.level.getEntityCount();
            }
        } catch (Exception ignored) {}

        return new GameStateSnapshot.RenderInfo(
            renderDistance, simDistance, fps, frameTime,
            chunksRendered, entitiesRendered, entitiesLoaded, particles
        );
    }

    private GameStateSnapshot.SystemInfo collectSystemInfo(Minecraft mc) {
        Runtime rt = Runtime.getRuntime();
        long usedMiB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long allocatedMiB = rt.totalMemory() / (1024 * 1024);
        long maxMiB = rt.maxMemory() / (1024 * 1024);

        String javaVersion = System.getProperty("java.version");
        String cpu = System.getProperty("os.arch");
        String gpu = null;
        Integer displayWidth = mc.getWindow().getWidth();
        Integer displayHeight = mc.getWindow().getHeight();
        String graphicsBackend = null;

        return new GameStateSnapshot.SystemInfo(
            javaVersion, usedMiB, allocatedMiB, maxMiB,
            cpu, gpu, displayWidth, displayHeight, graphicsBackend
        );
    }

    private GameStateSnapshot.TargetSection collectTarget(Minecraft mc, ClientLevel level, LocalPlayer player) {
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return new GameStateSnapshot.TargetSection("miss", null, null, null, null);
        }

        double distance = hit.getLocation().distanceTo(player.getEyePosition());

        if (hit instanceof BlockHitResult blockHit) {
            BlockPos bp = blockHit.getBlockPos();
            BlockState state = level.getBlockState(bp);
            String blockId = state.getBlock().toString();
            Direction side = blockHit.getDirection();
            String sideName = side.getSerializedName();

            Map<String, String> properties = new LinkedHashMap<>();
            for (Property<?> p : state.getProperties()) {
                String key = p.getName();
                String val = state.getValue(p).toString();
                properties.put(key, val);
            }

            List<String> tags = new ArrayList<>();
            try {
                state.typeHolder().tags().forEach(t ->
                    tags.add(t.location().toString()));
            } catch (Exception ignored) {}
            tags.sort(String::compareTo);

            GameStateSnapshot.FluidTarget fluid = null;
            FluidState fluidState = level.getFluidState(bp);
            if (!fluidState.isEmpty()) {
                Map<String, String> fluidProps = new LinkedHashMap<>();
                List<String> fluidTags = new ArrayList<>();
                fluidTags.sort(String::compareTo);
                fluid = new GameStateSnapshot.FluidTarget(fluidState.getType().toString(), fluidProps, fluidTags);
            }

            return new GameStateSnapshot.TargetSection(
                "block", distance,
                new GameStateSnapshot.BlockTarget(
                    blockId,
                    new GameStateSnapshot.Vec3i(bp.getX(), bp.getY(), bp.getZ()),
                    sideName, properties, tags
                ),
                fluid, null
            );
        } else if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            Vec3 epos = entity.getPosition(0f);
            double entityDist = entity.getPosition(0f).distanceTo(player.getEyePosition());
            return new GameStateSnapshot.TargetSection(
                "entity", entityDist, null, null,
                new GameStateSnapshot.EntityTarget(
                    entity.getId(),
                    entity.getType().toString(),
                    entity.getName().getString(),
                    entity.getUUID().toString(),
                    new GameStateSnapshot.Vec3(
                        epos.x,
                        epos.y,
                        epos.z
                    ),
                    entityDist
                )
            );
        }

        return new GameStateSnapshot.TargetSection("miss", null, null, null, null);
    }

    private static String facingDirection(float yaw) {
        float normalized = Mth.wrapDegrees(yaw);
        if (normalized >= -45 && normalized < 45) return "south";
        if (normalized >= 45 && normalized < 135) return "west";
        if (normalized >= -135 && normalized < -45) return "east";
        return "north";
    }
}
