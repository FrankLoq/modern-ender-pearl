package com.frankloq;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/modern_ender_pearl.json");

    // Parameters
    public boolean loadChunks = true;
    public boolean loadChunksWhileOwnerIsGone = true;
    public boolean destroyAtDeath = false;
    public boolean canGoThroughPortals = true;
    public boolean canTeleportBetweenDimensions = true;
    public boolean canVehicleWithPassengersCrossPortals = true;
    public boolean protectStasisChambers = true;

    // Troll mode
    public List<String> trolledPlayers = new ArrayList<>(List.of("Herobrine"));
    public String trollJoinMessage = "&c[ModernEnderPearl] &7The &e%player% &7is &cNOT &7allowed to use the mod because he sucks";
    public boolean broadcastTrollMessageToEveryone = true;

    public static ModConfig INSTANCE = new ModConfig();

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                INSTANCE = GSON.fromJson(reader, ModConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        save();
    }

    public static void save() {
        CONFIG_FILE.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}