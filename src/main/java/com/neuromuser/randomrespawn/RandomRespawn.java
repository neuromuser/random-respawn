package com.neuromuser.randomrespawn;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
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

import java.nio.file.Path;

public class RandomRespawn implements ModInitializer {
    private static Path configPath;

    @Override
    public void onInitialize() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("randomrespawn.json");
        ConfigManager.load(configPath);
        ConfigNetworking.init();
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            ConfigNetworking.sendToClient(player);
            String uuidStr = player.getUuidAsString();
            if (!ConfigManager.get().playerSettings.containsKey(uuidStr)) {
                teleportPlayer(player);
                ConfigManager.get().playerSettings.put(uuidStr, ConfigManager.get().defaultEnabled);
                ConfigManager.save(configPath);
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) {
                teleportPlayer(newPlayer);
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
            ConfigManager.save(configPath);

            context.getSource().sendFeedback(() -> Text.literal("Random respawn for " + targetPlayer.getName().getString() + " is now " + (enabled ? "enabled" : "disabled")), true);
            return 1;
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private int setDefaultSetting(CommandContext<ServerCommandSource> context) {
        ConfigManager.get().defaultEnabled = BoolArgumentType.getBool(context, "enabled");
        ConfigManager.save(configPath);

        context.getSource().sendFeedback(() -> Text.literal("Default random respawn is now " + (ConfigManager.get().defaultEnabled ? "enabled" : "disabled")), true);
        return 1;
    }

    private int setRange(CommandContext<ServerCommandSource> context) {
        ConfigManager.get().respawnRange = IntegerArgumentType.getInteger(context, "distance");
        ConfigManager.save(configPath);

        context.getSource().sendFeedback(() -> Text.literal("Range set to " + ConfigManager.get().respawnRange), true);
        return 1;
    }

    private int showInfo(CommandContext<ServerCommandSource> context) {
        Config config = ConfigManager.get();
        context.getSource().sendFeedback(() -> Text.literal("=== Random Respawn Settings ===\n" + "Default: " + config.defaultEnabled + "\n" + "Range: " + config.respawnRange + "\n" + "Tracked Players: " + config.playerSettings.size()), false);
        return 1;
    }

    private void teleportPlayer(ServerPlayerEntity player) {
        Config config = ConfigManager.get();
        boolean enabled = config.playerSettings.getOrDefault(player.getUuidAsString(), config.defaultEnabled);

        if (!enabled) return;

        player.sendMessage(Text.translatable("com.neuromuser.randomrespawn.teleporting").formatted(Formatting.RED, Formatting.BOLD), true);
        tryToTeleport(player, 0);
    }

    private void tryToTeleport(ServerPlayerEntity player, int iteration) {
        if (iteration > 1000) return;

        ServerWorld world = player.getServerWorld();
        double rx = getRandomCoordinate();
        double rz = getRandomCoordinate();

        int ix = (int) Math.floor(rx);
        int iz = (int) Math.floor(rz);

        if (isChunkVisited(world, ix, iz)) {
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

    public double getRandomCoordinate() {
        int range = ConfigManager.get().respawnRange;
        return (Math.random() * (range * 2)) - range;
    }

    public boolean isChunkVisited(ServerWorld world, int x, int z) {
        WorldChunk chunk = world.getChunk(x >> 4, z >> 4);
        return chunk.getInhabitedTime() > 0;
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