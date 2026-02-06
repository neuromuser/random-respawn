package com.neuromuser.randomrespawn;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.tag.BlockTags;
import net.minecraft.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RandomRespawn implements ModInitializer {
    private static Path configPath;
    private final Map<UUID, BlockPos> pendingRespawns = new HashMap<>();

    @Override
    public void onInitialize() {
        ConfigNetworking.init();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path worldConfigPath = server.getSavePath(WorldSavePath.ROOT)
                    .resolve("randomrespawn.json");
            ConfigManager.load(worldConfigPath);
            configPath = worldConfigPath;
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
            if (entity instanceof ServerPlayerEntity player) {
                Config config = ConfigManager.get();
                boolean enabled = config.playerSettings.getOrDefault(
                        player.getUuidAsString(),
                        config.defaultEnabled
                );

                if (enabled) {
                    ServerWorld world = player.getWorld();
                    UUID playerUuid = player.getUuid();
                    int playerId = player.getId();

                    world.getServer().execute(() -> findAndPreloadRespawnLocation(world, playerUuid, playerId));
                }
            }
            return true;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            ConfigNetworking.sendToClient(player);
            String uuidStr = player.getUuidAsString();
            if (!ConfigManager.get().playerSettings.containsKey(uuidStr)) {
                teleportPlayer(player);
                ConfigManager.get().playerSettings.put(uuidStr, ConfigManager.get().defaultEnabled);
                if (configPath != null) {
                    ConfigManager.save(configPath);
                }
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) {
                UUID uuid = newPlayer.getUuid();
                BlockPos respawnPos = pendingRespawns.remove(uuid);

                if (respawnPos != null) {
                    teleportToPreloaded(newPlayer, respawnPos);
                } else {
                    teleportPlayer(newPlayer);
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> pendingRespawns.remove(handler.player.getUuid()));

        registerCommands();
    }

    private void findAndPreloadRespawnLocation(ServerWorld world, UUID playerUuid, int playerId) {
        searchNextValidPosition(world, playerUuid, playerId, 0);
    }

    private void searchNextValidPosition(ServerWorld world, UUID playerUuid, int playerId, int iteration) {
        if (iteration > 5000) {
            System.err.println("RandomRespawn: Failed to find unvisited chunk after 5000 attempts for player " + playerUuid);
            return;
        }

        for (int i = 0; i < 10 && iteration + i <= 5000; i++) {
            int currentIteration = iteration + i;
            int rangeExpansion = (currentIteration / 10) * 100;
            double rx = getRandomCoordinate(rangeExpansion);
            double rz = getRandomCoordinate(rangeExpansion);

            int ix = (int) Math.floor(rx);
            int iz = (int) Math.floor(rz);
            int chunkX = ix >> 4;
            int chunkZ = iz >> 4;

            WorldChunk chunk = world.getChunk(chunkX, chunkZ);

            if (chunk.getInhabitedTime() > 0) {
                continue;
            }

            int y = getSafeSurfaceY(world, ix, iz);
            if (y <= world.getBottomY()) {
                continue;
            }

            BlockPos pos = new BlockPos(ix, y, iz);
            pendingRespawns.put(playerUuid, pos);

            ChunkPos chunkPos = new ChunkPos(pos);
            world.getChunkManager().addTicket(
                    ChunkTicketType.POST_TELEPORT,
                    chunkPos,
                    3,
                    playerId
            );
            return;
        }

        world.getServer().execute(() ->
                searchNextValidPosition(world, playerUuid, playerId, iteration + 10)
        );
    }

    private void teleportToPreloaded(ServerPlayerEntity player, BlockPos pos) {
        ServerWorld world = player.getWorld();
        player.teleport(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                player.getYaw(), player.getPitch());
        player.setVelocity(0, 0, 0);
        player.fallDistance = 0;
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(CommandManager.literal("randomrespawn")
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

            context.getSource().sendFeedback(Text.literal("Random respawn for " + targetPlayer.getName().getString() + " is now " + (enabled ? "enabled" : "disabled")), true);
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

        context.getSource().sendFeedback(Text.literal("Default random respawn is now " + (ConfigManager.get().defaultEnabled ? "enabled" : "disabled")), true);
        return 1;
    }

    private int setRange(CommandContext<ServerCommandSource> context) {
        ConfigManager.get().respawnRange = IntegerArgumentType.getInteger(context, "distance");
        if (configPath != null) {
            ConfigManager.save(configPath);
        }

        context.getSource().sendFeedback(Text.literal("Range set to " + ConfigManager.get().respawnRange), true);
        return 1;
    }

    private int showInfo(CommandContext<ServerCommandSource> context) {
        Config config = ConfigManager.get();
        context.getSource().sendFeedback(Text.literal("=== Random Respawn Settings ===\n" + "Default: " + config.defaultEnabled + "\n" + "Range: " + config.respawnRange + "\n" + "Tracked Players: " + config.playerSettings.size()), false);
        return 1;
    }

    private void teleportPlayer(ServerPlayerEntity player) {
        Config config = ConfigManager.get();
        boolean enabled = config.playerSettings.getOrDefault(player.getUuidAsString(), config.defaultEnabled);

        if (!enabled) return;

        tryToTeleport(player, 0);
    }

    private void tryToTeleport(ServerPlayerEntity player, int iteration) {
        if (iteration > 1000) {
            System.err.println("RandomRespawn: Failed to find unvisited chunk for first join after 1000 attempts");
            return;
        }

        ServerWorld world = player.getWorld();

        int rangeExpansion = (iteration / 10) * 100;
        double rx = getRandomCoordinate(rangeExpansion);
        double rz = getRandomCoordinate(rangeExpansion);

        int ix = (int) Math.floor(rx);
        int iz = (int) Math.floor(rz);
        int chunkX = ix >> 4;
        int chunkZ = iz >> 4;

        WorldChunk chunk = world.getChunk(chunkX, chunkZ);

        if (chunk.getInhabitedTime() > 0) {
            tryToTeleport(player, iteration + 1);
            return;
        }

        int y = getSafeSurfaceY(world, ix, iz);
        if (y <= world.getBottomY()) {
            tryToTeleport(player, iteration + 1);
            return;
        }

        player.teleport(world, ix + 0.5, y, iz + 0.5, player.getYaw(), player.getPitch());
        player.setVelocity(0, 0, 0);
        player.fallDistance = 0;
    }

    public double getRandomCoordinate(int rangeExpansion) {
        int range = ConfigManager.get().respawnRange + rangeExpansion;
        return (Math.random() * (range * 2)) - range;
    }

    private int getSafeSurfaceY(ServerWorld world, int x, int z) {
        int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        if (surface <= world.getBottomY()) return world.getBottomY() - 1;

        BlockPos pos = new BlockPos(x, surface, z);
        BlockState ground = world.getBlockState(pos.down());
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());

        if (isHazardous(ground) || isBlocked(feet, world, pos) || isBlocked(head, world, pos.up())) {
            return world.getBottomY() - 1;
        }
        return surface;
    }

    private boolean isBlocked(BlockState state, ServerWorld world, BlockPos pos) {
        return !state.isAir() && (!state.getFluidState().isEmpty() || !state.getCollisionShape(world, pos).isEmpty());
    }

    private boolean isHazardous(BlockState state) {
        return state.getFluidState().isIn(FluidTags.LAVA) || state.isIn(BlockTags.FIRE) ||
                state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.POWDER_SNOW);
    }
}