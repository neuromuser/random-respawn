package com.neuromuser.randomrespawn;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config config = new Config();
    private static Config serverConfig = null;
    private static boolean hasServerMod = false;

    public static Config get() {
        return hasServerMod && serverConfig != null ? serverConfig : config;
    }

    public static boolean shouldRunServerLogic() {
        return hasServerMod;
    }

    public static void load(Path path) {
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path);
                config = GSON.fromJson(json, Config.class);
            } else {
                save(path);
            }
        } catch (IOException e) {
            System.err.println("Failed to load config: " + e.getMessage());
        }
    }

    public static void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config));
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    public static String toJson() {
        return GSON.toJson(config);
    }

    public static void receiveServerConfig(String json) {
        serverConfig = GSON.fromJson(json, Config.class);
        hasServerMod = true;
    }
}
