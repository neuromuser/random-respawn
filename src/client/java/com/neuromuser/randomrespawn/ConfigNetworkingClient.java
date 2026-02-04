package com.neuromuser.randomrespawn;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class ConfigNetworkingClient {
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(
            ConfigNetworking.ConfigPayload.ID,
            (payload, context) -> {
                context.client().execute(() ->
                    ConfigManager.receiveServerConfig(payload.json())
                );
            }
        );
    }
}
