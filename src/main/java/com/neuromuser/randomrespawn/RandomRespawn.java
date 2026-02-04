package com.neuromuser.randomrespawn;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class RandomRespawn implements ModInitializer {
    private static final Map<UUID, Boolean> playerSettings = new HashMap<>();
    private static final Set<UUID> hasJoinedBefore = new HashSet<>();
    private static boolean defaultEnabled = true;
    private static int respawnRange = 10000;

    @Override
    public void onInitialize() {
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) {
                teleportPlayer(newPlayer);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            UUID playerId = player.getUuid();

            if (!hasJoinedBefore.contains(playerId)) {
                hasJoinedBefore.add(playerId);
                teleportPlayer(player);
            }
        });

        registerCommands();
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
                        .then(CommandManager.argument("distance", IntegerArgumentType.integer(100, 100000))
                                .executes(this::setRange)))

                .then(CommandManager.literal("info")
                        .executes(this::showInfo))
        ));
    }

    private int setPlayerSetting(CommandContext<ServerCommandSource> context) {
        try {
            ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");
            boolean enabled = BoolArgumentType.getBool(context, "enabled");

            playerSettings.put(targetPlayer.getUuid(), enabled);

            context.getSource().sendFeedback(
                    () -> Text.literal("Random respawn for " + targetPlayer.getName().getString() +
                            " is now " + (enabled ? "enabled" : "disabled")),
                    true
            );

            return 1;
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private int setDefaultSetting(CommandContext<ServerCommandSource> context) {
        defaultEnabled = BoolArgumentType.getBool(context, "enabled");

        context.getSource().sendFeedback(
                () -> Text.literal("Default random respawn setting is now " +
                        (defaultEnabled ? "enabled" : "disabled") + " for new players"),
                true
        );

        return 1;
    }

    private int setRange(CommandContext<ServerCommandSource> context) {
        respawnRange = IntegerArgumentType.getInteger(context, "distance");

        context.getSource().sendFeedback(
                () -> Text.literal("Random respawn range set to " + respawnRange + " blocks"),
                true
        );

        return 1;
    }

    private int showInfo(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(
                () -> Text.literal("=== Random Respawn Settings ===\n" +
                        "Default enabled: " + defaultEnabled + "\n" +
                        "Respawn range: " + respawnRange + " blocks\n" +
                        "Players with custom settings: " + playerSettings.size()),
                false
        );

        return 1;
    }

    private void teleportPlayer(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        boolean enabled = playerSettings.getOrDefault(playerId, defaultEnabled);

        if (!enabled) {
            return;
        }

        player.sendMessage(Text.translatable("com.neuromuser.randomrespawn.teleporting").formatted(Formatting.RED, Formatting.BOLD), true);
        tryToTeleport(player, 0);
    }

    private void tryToTeleport(ServerPlayerEntity player, int iteration) {
        ServerWorld world = player.getServerWorld();
        double x = player.getX();
        double z = player.getZ();

        int rx = (int) Math.floor(getRandomCoordinate(iteration, x));
        int rz = (int) Math.floor(getRandomCoordinate(iteration, z));

        world.getChunk(rx >> 4, rz >> 4);

        if (isChunkVisited(world, rx, rz)) {
            tryToTeleport(player, iteration + 1);
            return;
        }

        int y = getSafeSurfaceY(world, rx, rz);
        if (y <= world.getBottomY()) {
            tryToTeleport(player, iteration + 1);
            return;
        }

        double fx = rx + 0.5;
        double fz = rz + 0.5;
        player.teleport(world, fx, y, fz, player.getYaw(), player.getPitch());
        player.setVelocity(0, 0, 0);
        player.fallDistance = 0;
    }

    public double getRandomCoordinate(int iteration, double subtract) {
        return (Math.random() * (respawnRange + iteration * 100.0)) - subtract;
    }

    public boolean isChunkVisited(ServerWorld world, int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        WorldChunk chunk = world.getChunk(chunkX, chunkZ);
        return chunk.getInhabitedTime() > 0;
    }

    private int getSafeSurfaceY(ServerWorld world, int x, int z) {
        int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        BlockPos groundPos = new BlockPos(x, surface - 1, z);
        BlockPos feetPos = new BlockPos(x, surface, z);
        BlockPos headPos = feetPos.up();

        if (surface <= world.getBottomY()) return world.getBottomY() - 1;

        BlockState ground = world.getBlockState(groundPos);
        BlockState feet = world.getBlockState(feetPos);
        BlockState head = world.getBlockState(headPos);

        if (isHazardous(ground)) return world.getBottomY() - 1;
        if (!isClear(feet, world, feetPos)) return world.getBottomY() - 1;
        if (!isClear(head, world, headPos)) return world.getBottomY() - 1;

        return surface;
    }

    private boolean isClear(BlockState state, ServerWorld world, BlockPos pos) {
        if (state.isAir()) return true;
        if (!state.getFluidState().isEmpty()) return false;
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private boolean isHazardous(BlockState state) {
        if (state.getFluidState().isIn(FluidTags.LAVA)) return true;
        if (state.isIn(BlockTags.FIRE)) return true;

        return state.getBlock() == Blocks.MAGMA_BLOCK ||
                state.getBlock() == Blocks.CAMPFIRE ||
                state.getBlock() == Blocks.SOUL_CAMPFIRE ||
                state.getBlock() == Blocks.CACTUS ||
                state.getBlock() == Blocks.SWEET_BERRY_BUSH ||
                state.getBlock() == Blocks.WITHER_ROSE ||
                state.getBlock() == Blocks.POWDER_SNOW;
    }
}