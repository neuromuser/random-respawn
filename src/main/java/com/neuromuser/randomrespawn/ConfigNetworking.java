package com.neuromuser.randomrespawn;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class ConfigNetworking {
    private static final Identifier SYNC_ID = new Identifier("random-respawn", "config");

    public static void init() {
    }

    public static void sendToClient(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(ConfigManager.toJson());
        ServerPlayNetworking.send(player, SYNC_ID, buf);
    }
}