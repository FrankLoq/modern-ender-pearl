package com.frankloq.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "remove", at = @At("HEAD"))
    private void onEntityRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        if ((Object) this instanceof ThrownEnderpearl pearl) {
            Level level = pearl.level();

            if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
                ChunkPos pos = pearl.chunkPosition();
                this.safelyUnforceChunk(serverLevel, pos, pearl);
            }
        }
    }

    @Unique
    private void safelyUnforceChunk(ServerLevel serverLevel, ChunkPos pos, ThrownEnderpearl dyingPearl) {
        if (pos == null) return;

        int startX = pos.x * 16;
        int startZ = pos.z * 16;
        int endX = startX + 16;
        int endZ = startZ + 16;

        AABB chunkBox = new AABB(
                startX, serverLevel.getMinBuildHeight(), startZ,
                endX, serverLevel.getMaxBuildHeight(), endZ
        );

        List<ThrownEnderpearl> otherPearls = serverLevel.getEntitiesOfClass(
                ThrownEnderpearl.class,
                chunkBox,
                entity -> entity != dyingPearl && entity.isAlive()
        );

        if (otherPearls.isEmpty()) {
            serverLevel.setChunkForced(pos.x, pos.z, false);
        }
    }
}