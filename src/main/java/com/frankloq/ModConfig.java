package com.frankloq;

import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.List;

public class ModConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Parameters
    public static final ModConfigSpec.BooleanValue LOAD_CHUNKS = BUILDER.comment("Load chunks where pearls are").define("loadChunks", true);
    public static final ModConfigSpec.BooleanValue LOAD_CHUNKS_WHILE_OWNER_IS_GONE = BUILDER.comment("Load chunks while owner is gone").define("loadChunksWhileOwnerIsGone", true);
    public static final ModConfigSpec.BooleanValue DESTROY_AT_DEATH = BUILDER.comment("Destroy pearl at death").define("destroyAtDeath", false);
    public static final ModConfigSpec.BooleanValue CAN_GO_THROUGH_PORTALS = BUILDER.comment("Allow pearls to go through portals").define("canGoThroughPortals", true);
    public static final ModConfigSpec.BooleanValue CAN_TELEPORT_BETWEEN_DIMENSIONS = BUILDER.comment("Allow cross-dimensional teleportation").define("canTeleportBetweenDimensions", true);
    public static final ModConfigSpec.BooleanValue PROTECT_STASIS_CHAMBERS = BUILDER.comment("Protect stasis chambers").define("protectStasisChambers", true);

    public static final ModConfigSpec CONFIG = BUILDER.build();

    // Helper methods so the Mixin code is easy to read
    public static boolean loadChunks() { return LOAD_CHUNKS.get(); }
    public static boolean loadChunksWhileOwnerIsGone() { return LOAD_CHUNKS_WHILE_OWNER_IS_GONE.get(); }
    public static boolean destroyAtDeath() { return DESTROY_AT_DEATH.get(); }
    public static boolean canGoThroughPortals() { return CAN_GO_THROUGH_PORTALS.get(); }
    public static boolean canTeleportBetweenDimensions() { return CAN_TELEPORT_BETWEEN_DIMENSIONS.get(); }
    public static boolean protectStasisChambers() { return PROTECT_STASIS_CHAMBERS.get(); }
}