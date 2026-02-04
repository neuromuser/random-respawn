package com.neuromuser.randomrespawn;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class ConfigNetworking {
    public record ConfigPayload(String json) implements CustomPayload {
        public static final CustomPayload.Id<ConfigPayload> ID =
            new CustomPayload.Id<>(Identifier.of("random-respawn", "config"));

        public static final PacketCodec<RegistryByteBuf, ConfigPayload> CODEC =
            PacketCodec.of(
                (value, buf) -> buf.writeString(value.json),
                buf -> new ConfigPayload(buf.readString())
            );

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void init() {
        PayloadTypeRegistry.playS2C().register(ConfigPayload.ID, ConfigPayload.CODEC);

    }

    public static void sendToClient(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new ConfigPayload(ConfigManager.toJson()));
    }
}
