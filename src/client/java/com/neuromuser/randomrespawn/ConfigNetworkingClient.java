package com.neuromuser.randomrespawn;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.Identifier;

public class ConfigNetworkingClient {
    private static final Identifier SYNC_ID = new Identifier("random-respawn", "config");

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SYNC_ID, (client, handler, buf, responseSender) -> {
            String json = buf.readString();
            client.execute(() -> ConfigManager.receiveServerConfig(json));
        });
    }
}
