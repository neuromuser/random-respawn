package com.neuromuser.randomrespawn;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class RandomRespawnClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ConfigNetworking.PROGRESS_ID, (client, handler, buf, responseSender) -> {
            String key = buf.readString();
            int progress = buf.readInt();

            client.execute(() -> {
                if (progress >= 100) {
                    if (client.currentScreen instanceof RespawnLoadingScreen) {
                        client.setScreen(null);
                    }
                } else {
                    if (!(client.currentScreen instanceof RespawnLoadingScreen)) {
                        client.setScreen(new RespawnLoadingScreen());
                    }
                    if (client.currentScreen instanceof RespawnLoadingScreen screen) {
                        screen.updateProgress(key, progress);
                    }
                }
            });
        });
    }
}