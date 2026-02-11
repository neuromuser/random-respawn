package com.neuromuser.randomrespawn;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RandomRespawn implements ModInitializer {
    private static Path configPath;
    private final Map<UUID, Integer> playerRetryCount = new HashMap<>();
    private final Map<UUID, LoadingState> activeLoaders = new ConcurrentHashMap<>();

    private static class LoadingState {
        final BlockPos targetPos;
        final Set<ChunkPos> chunksToLoad;
        final Set<ChunkPos> loadedChunks;
        int ticks;
        int ticksAfterTeleport;
        final ServerWorld world;
        boolean teleported;

        LoadingState(ServerWorld world, BlockPos targetPos) {
            this.world = world;
            this.targetPos = targetPos;
            this.ticks = 0;
            this.ticksAfterTeleport = 0;
            this.teleported = false;
            this.chunksToLoad = new HashSet<>();
            this.loadedChunks = new HashSet<>();

            ChunkPos centerChunk = new ChunkPos(targetPos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    chunksToLoad.add(new ChunkPos(centerChunk.x + dx, centerChunk.z + dz));
                }
            }
        }
    }

    @Override
    public void onInitialize() {
        ConfigNetworking.init();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path worldConfigPath = server.getSavePath(WorldSavePath.ROOT).resolve("randomrespawn.json");
            ConfigManager.load(worldConfigPath);
            configPath = worldConfigPath;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeLoaders.isEmpty()) return;

            Iterator<Map.Entry<UUID, LoadingState>> it = activeLoaders.entrySet().iterator();
            while (it.hasNext()) {
                try {
                    Map.Entry<UUID, LoadingState> entry = it.next();
                    UUID uuid = entry.getKey();
                    LoadingState state = entry.getValue();

                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                    if (player == null || player.isRemoved()) {
                        it.remove();
                        continue;
                    }

                    state.ticks++;

                    for (ChunkPos cp : state.chunksToLoad) {
                        state.world.getChunkManager().addTicket(ChunkTicketType.POST_TELEPORT, cp, 2, player.getId());
                        if (state.world.getChunk(cp.x, cp.z, ChunkStatus.FULL, false) != null) {
                            state.loadedChunks.add(cp);
                        }
                    }

                    int actualProgress = (int) ((state.loadedChunks.size() / (float) state.chunksToLoad.size()) * 100);
                    int displayProgress = Math.min(actualProgress, Math.min(95, state.ticks * 3));

                    ConfigNetworking.sendProgress(player, "randomrespawn.generating", displayProgress);

                    if (state.loadedChunks.size() >= state.chunksToLoad.size() && !state.teleported && state.ticks >= 30) {
                        player.teleport(state.world, state.targetPos.getX() + 0.5, state.targetPos.getY(), state.targetPos.getZ() + 0.5,
                                player.getYaw(), player.getPitch());
                        player.setVelocity(0, 0, 0);
                        player.fallDistance = 0;
                        state.teleported = true;
                    }

                    if (state.teleported) {
                        state.ticksAfterTeleport++;

                        int finalProgress = Math.min(100, 95 + (state.ticksAfterTeleport / 8));
                        ConfigNetworking.sendProgress(player, "randomrespawn.generating", finalProgress);

                        if (state.ticksAfterTeleport >= 60) {
                            ConfigNetworking.sendProgress(player, "randomrespawn.ready", 100);
                            removeInvulnerability(player);
                            playerRetryCount.remove(uuid);
                            it.remove();
                        }
                    }

                    if (state.ticks > 400) {
                        retrySearch(state.world, player);
                        it.remove();
                    }
                } catch (Exception e) {
                    it.remove();
                }
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            ConfigNetworking.sendToClient(player);
            String uuidStr = player.getUuidAsString();
            if (!ConfigManager.get().playerSettings.containsKey(uuidStr)) {
                teleportPlayerSync(player);
                ConfigManager.get().playerSettings.put(uuidStr, ConfigManager.get().defaultEnabled);
                if (configPath != null) {
                    ConfigManager.save(configPath);
                }
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) {
                Config config = ConfigManager.get();
                boolean enabled = config.playerSettings.getOrDefault(
                        newPlayer.getUuidAsString(),
                        config.defaultEnabled
                );

                if (enabled) {
                    makePlayerInvulnerable(newPlayer);
                    ConfigNetworking.sendProgress(newPlayer, "randomrespawn.searching", 0);

                    ServerWorld world = newPlayer.getServerWorld();
                    world.setTimeOfDay(1000L);

                    newPlayer.setExperienceLevel(0);
                    newPlayer.setExperiencePoints(0);
                    newPlayer.addExperience(0);
                    var server = newPlayer.getServer();
                    if (server != null) {
                        var advancementLoader = server.getAdvancementLoader();
                        var playerAdvancements = newPlayer.getAdvancementTracker();

                        for (Advancement advancement : advancementLoader.getAdvancements()) {
                            AdvancementProgress progress = playerAdvancements.getProgress(advancement);

                            if (progress.isAnyObtained()) {
                                for (String criterion : progress.getObtainedCriteria()) {
                                    playerAdvancements.revokeCriterion(advancement, criterion);
                                }
                            }
                        }
                    }

                    world.getServer().execute(() ->
                            findLocationAsync(world, newPlayer.getUuid(), 0)
                    );
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUuid();
            playerRetryCount.remove(uuid);
            activeLoaders.remove(uuid);
        });

        registerCommands();
    }

    private void makePlayerInvulnerable(ServerPlayerEntity player) {
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 999999, 255, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 999999, 255, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, 999999, 255, false, false));
        player.setInvulnerable(true);
    }

    private void removeInvulnerability(ServerPlayerEntity player) {
        player.removeStatusEffect(StatusEffects.RESISTANCE);
        player.removeStatusEffect(StatusEffects.FIRE_RESISTANCE);
        player.removeStatusEffect(StatusEffects.WATER_BREATHING);
        player.setInvulnerable(false);
    }

    private void findLocationAsync(ServerWorld world, UUID playerUuid, int iteration) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(playerUuid);

        if (iteration > 50) {
            if (player != null) {
                removeInvulnerability(player);
                playerRetryCount.remove(playerUuid);
                ConfigNetworking.sendProgress(player, "randomrespawn.ready", 100);
            }
            return;
        }

        if (player == null || player.isRemoved()) {
            return;
        }

        int rangeExpansion = (iteration / 5) * 200;
        double rx = getRandomCoordinate(rangeExpansion);
        double rz = getRandomCoordinate(rangeExpansion);
        int chunkX = ((int) Math.floor(rx)) >> 4;
        int chunkZ = ((int) Math.floor(rz)) >> 4;
        ChunkPos cp = new ChunkPos(chunkX, chunkZ);

        world.getChunkManager().threadedAnvilChunkStorage.getNbt(cp).thenAcceptAsync(nbtOpt -> {
            boolean isUnvisited = true;

            if (nbtOpt.isPresent()) {
                NbtCompound nbt = nbtOpt.get();
                if (nbt.contains("InhabitedTime", 4)) {
                    isUnvisited = nbt.getLong("InhabitedTime") == 0;
                }
            }

            if (!isUnvisited) {
                findLocationAsync(world, playerUuid, iteration + 1);
                return;
            }

            ServerPlayerEntity currentPlayer = world.getServer().getPlayerManager().getPlayer(playerUuid);
            if (currentPlayer == null || currentPlayer.isRemoved()) return;

            world.getServer().execute(() -> {
                Chunk chunk = world.getChunk(cp.x, cp.z);
                BlockPos spawnPos = findSurfaceSpawn(world, cp);

                if (spawnPos != null) {
                    ConfigNetworking.sendProgress(currentPlayer, "randomrespawn.generating", 0);
                    activeLoaders.put(playerUuid, new LoadingState(world, spawnPos));
                } else {
                    findLocationAsync(world, playerUuid, iteration + 1);
                }
            });

        }, world.getServer()).exceptionally(ex -> {
            findLocationAsync(world, playerUuid, iteration + 1);
            return null;
        });
    }

    private BlockPos findSurfaceSpawn(ServerWorld world, ChunkPos cp) {
        int baseX = cp.getStartX();
        int baseZ = cp.getStartZ();

        int[][] checkOffsets = {
                {8, 8}, {4, 4}, {12, 4}, {4, 12}, {12, 12},
                {8, 4}, {4, 8}, {12, 8}, {8, 12},
                {6, 6}, {10, 10}, {6, 10}, {10, 6}
        };

        for (int[] offset : checkOffsets) {
            int x = baseX + offset[0];
            int z = baseZ + offset[1];

            int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
            if (surfaceY <= world.getBottomY()) continue;

            BlockPos checkPos = new BlockPos(x, surfaceY, z);

            if (!world.isSkyVisible(checkPos)) continue;

            BlockPos groundPos = checkPos.down();
            BlockState ground = world.getBlockState(groundPos);
            BlockState feet = world.getBlockState(checkPos);
            BlockState head = world.getBlockState(checkPos.up());

            if (!ground.isSolidBlock(world, groundPos)) continue;
            if (isHazardous(ground) || isWater(ground)) continue;
            if (!feet.isAir() || !head.isAir()) continue;
            if (isHazardous(feet) || isHazardous(head)) continue;

            return checkPos;
        }

        return null;
    }

    private void retrySearch(ServerWorld world, ServerPlayerEntity player) {
        UUID playerUuid = player.getUuid();
        int currentRetries = playerRetryCount.getOrDefault(playerUuid, 0);
        playerRetryCount.put(playerUuid, currentRetries + 1);

        ConfigNetworking.sendProgress(player, "randomrespawn.searching", 0);

        world.getServer().execute(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            findLocationAsync(world, playerUuid, currentRetries);
        });
    }

    private void teleportPlayerSync(ServerPlayerEntity player) {
        Config config = ConfigManager.get();
        boolean enabled = config.playerSettings.getOrDefault(
                player.getUuidAsString(), config.defaultEnabled);

        if (!enabled) return;

        ServerWorld world = player.getServerWorld();

        for (int iteration = 0; iteration < 50; iteration++) {
            int rangeExpansion = (iteration / 10) * 100;
            double rx = getRandomCoordinate(rangeExpansion);
            double rz = getRandomCoordinate(rangeExpansion);
            int chunkX = ((int) Math.floor(rx)) >> 4;
            int chunkZ = ((int) Math.floor(rz)) >> 4;

            Chunk chunk = world.getChunk(chunkX, chunkZ);
            if (chunk.getInhabitedTime() > 0) {
                continue;
            }

            BlockPos spawnPos = findSurfaceSpawn(world, new ChunkPos(chunkX, chunkZ));
            if (spawnPos != null) {
                player.teleport(world, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                        player.getYaw(), player.getPitch());
                player.setVelocity(0, 0, 0);
                player.fallDistance = 0;
                return;
            }
        }
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("randomrespawn")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("set")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                                .executes(this::setPlayerSetting))))
                        .then(CommandManager.literal("default")
                                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                        .executes(this::setDefaultSetting)))
                        .then(CommandManager.literal("range")
                                .then(CommandManager.argument("distance", IntegerArgumentType.integer(100, 1000000))
                                        .executes(this::setRange)))
                        .then(CommandManager.literal("info")
                                .executes(this::showInfo))
                ));
    }

    private int setPlayerSetting(CommandContext<ServerCommandSource> context) {
        try {
            ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");
            boolean enabled = BoolArgumentType.getBool(context, "enabled");

            ConfigManager.get().playerSettings.put(targetPlayer.getUuidAsString(), enabled);
            if (configPath != null) {
                ConfigManager.save(configPath);
            }

            context.getSource().sendFeedback(() -> Text.literal("Random respawn for " +
                    targetPlayer.getName().getString() + " is now " + (enabled ? "enabled" : "disabled")), true);
            return 1;
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private int setDefaultSetting(CommandContext<ServerCommandSource> context) {
        ConfigManager.get().defaultEnabled = BoolArgumentType.getBool(context, "enabled");
        if (configPath != null) {
            ConfigManager.save(configPath);
        }

        context.getSource().sendFeedback(() -> Text.literal("Default random respawn is now " +
                (ConfigManager.get().defaultEnabled ? "enabled" : "disabled")), true);
        return 1;
    }

    private int setRange(CommandContext<ServerCommandSource> context) {
        ConfigManager.get().respawnRange = IntegerArgumentType.getInteger(context, "distance");
        if (configPath != null) {
            ConfigManager.save(configPath);
        }

        context.getSource().sendFeedback(() -> Text.literal("Range set to " +
                ConfigManager.get().respawnRange), true);
        return 1;
    }

    private int showInfo(CommandContext<ServerCommandSource> context) {
        Config config = ConfigManager.get();
        context.getSource().sendFeedback(() -> Text.literal(
                "=== Random Respawn Settings ===\n" +
                        "Default: " + config.defaultEnabled + "\n" +
                        "Range: " + config.respawnRange + "\n" +
                        "Tracked Players: " + config.playerSettings.size()), false);
        return 1;
    }

    public double getRandomCoordinate(int rangeExpansion) {
        int range = ConfigManager.get().respawnRange + rangeExpansion;
        return (Math.random() * (range * 2)) - range;
    }

    private boolean isWater(BlockState state) {
        return state.getFluidState().isIn(FluidTags.WATER);
    }

    private boolean isHazardous(BlockState state) {
        return state.getFluidState().isIn(FluidTags.LAVA) ||
                state.isIn(BlockTags.FIRE);
    }
}