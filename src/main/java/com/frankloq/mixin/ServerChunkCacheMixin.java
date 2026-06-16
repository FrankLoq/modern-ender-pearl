package com.frankloq.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerChunkCache.class)
public class ServerChunkCacheMixin {

    @Shadow @Final ServerLevel level;

    @Inject(method = "addRegionTicket", at = @At("HEAD"), cancellable = true)
    private <T> void interceptPortalTicket(TicketType<T> type, ChunkPos pos, int radius, T value, CallbackInfo ci) {

        if (type == TicketType.PORTAL && com.frankloq.ModConfig.INSTANCE.protectStasisChambers) {

            // Calculate a 3x3 chunk area
            // (pos.x - 1) * 16 gets the absolute minimum X block of the grid
            // (pos.x + 2) * 16 gets the absolute maximum X block of the grid
            int startX = (pos.x - 1) * 16;
            int startZ = (pos.z - 1) * 16;
            int endX = (pos.x + 2) * 16;
            int endZ = (pos.z + 2) * 16;

            // Create a bounding box from the bottom of the world to the very top
            AABB searchBox = new AABB(
                    startX, level.getMinBuildHeight(), startZ,
                    endX, level.getMaxBuildHeight(), endZ
            );

            // Scan the area exclusively for Ender Pearls
            List<ThrownEnderpearl> pearls = level.getEntitiesOfClass(ThrownEnderpearl.class, searchBox);

            // If there is even a single pearl in that 3x3 chunk area
            if (!pearls.isEmpty()) {
                // Abort the chunk load and keep the chunks lazy
                ci.cancel();
            }
        }
    }
}