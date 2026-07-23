package com.frankloq.mixin;

import com.frankloq.ModConfig;
import com.frankloq.access.PearlTransitionAccessor;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void interceptPortalFlight(CallbackInfo ci) {
        ThrownEnderpearl pearl = (ThrownEnderpearl) (Object) this;

        if (pearl.level().isClientSide() || pearl.isRemoved() || pearl.isOnPortalCooldown() || !ModConfig.canGoThroughPortals()) return;

        Vec3 start = pearl.position();
        Vec3 end = start.add(pearl.getDeltaMovement());

        AABB flightBox = pearl.getBoundingBox().expandTowards(end.subtract(start));

        // Trace along the path vector without calling clip, this way we avoid interfering with Sable's collision logic
        double distance = start.distanceTo(end);
        int steps = Math.max(1, (int) (distance * 2.0));

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            double x = start.x + (end.x - start.x) * t;
            double y = start.y + (end.y - start.y) * t;
            double z = start.z + (end.z - start.z) * t;
            BlockPos pos = BlockPos.containing(x, y, z);

            // Get the chunk without forcing a load (create=false)
            net.minecraft.world.level.chunk.ChunkAccess chunk = pearl.level().getChunk(pos.getX() >> 4, pos.getZ() >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }

            BlockState state = chunk.getBlockState(pos);
            if (state.getBlock() instanceof NetherPortalBlock || state.getBlock() instanceof EndPortalBlock) {
                VoxelShape portalShape = state.getShape(pearl.level(), pos);

                if (!portalShape.isEmpty() && portalShape.bounds().move(pos).intersects(flightBox)) {
                    ServerLevel currentLevel = (ServerLevel) pearl.level();
                    ServerLevel targetLevel = state.getBlock() instanceof EndPortalBlock
                            ? currentLevel.getServer().getLevel(currentLevel.dimension() == Level.END ? Level.OVERWORLD : Level.END)
                            : currentLevel.getServer().getLevel(currentLevel.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER);

                    if (targetLevel != null) {
                        UUID ownerId = null;
                        if (pearl.getOwner() != null) {
                            ownerId = pearl.getOwner().getUUID();
                        } else {
                            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                            pearl.saveWithoutId(tag);
                            if (tag.hasUUID("Owner")) {
                                ownerId = tag.getUUID("Owner");
                            }
                        }

                        if (ownerId != null) {
                            ServerPlayer player = currentLevel.getServer().getPlayerList().getPlayer(ownerId);

                            if (player != null && !player.isSleeping()) {
                                player.teleportTo(currentLevel, pearl.getX(), pearl.getY(), pearl.getZ(), player.getYRot(), player.getXRot());

                                if (state.getBlock() instanceof net.minecraft.world.level.block.Portal portal) {
                                    DimensionTransition transition = portal.getPortalDestination(currentLevel, player, pos);

                                    if (transition != null) {
                                        Entity migratedEntity = player.changeDimension(transition);

                                        if (migratedEntity instanceof ServerPlayer targetPlayer) {
                                            targetPlayer.setPortalCooldown();
                                            ((PearlTransitionAccessor) targetPlayer).setPendingPearlEffect(true);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    pearl.discard();
                    ci.cancel();
                    return;
                }
            }
        }
    }

    // Scans a chunk to ensure no other pearls are relying on it before unloading!
    @Unique
    private void safelyUnforceChunk(ServerLevel serverLevel, ChunkPos pos, ThrownEnderpearl currentPearl) {
        if (pos == null) return;

        // Calculate the exact boundaries of the Chunk (16x16 blocks)
        int startX = pos.x * 16;
        int startZ = pos.z * 16;
        int endX = startX + 16;
        int endZ = startZ + 16;

        // Draw a box from bedrock to the sky limit
        AABB chunkBox = new AABB(startX, serverLevel.getMinBuildHeight(), startZ, endX, serverLevel.getMaxBuildHeight(), endZ);

        // Scan the chunk for any other pearl that is not the current pearl
        List<ThrownEnderpearl> otherPearls = serverLevel.getEntitiesOfClass(ThrownEnderpearl.class, chunkBox, entity -> entity != currentPearl && entity.isAlive());

        // If the chunk is completely empty of other pearls, it is safe to unload!
        if (otherPearls.isEmpty()) {
            serverLevel.setChunkForced(pos.x, pos.z, false);
        }
    }

    // The chunk loading logic
    @Unique
    private ChunkPos loadedChunkPos = null;

    @Inject(method = "tick", at = @At("TAIL"))
    private void loadPearlChunks(CallbackInfo ci) {
        ThrownEnderpearl pearl = (ThrownEnderpearl) (Object) this;
        Level level = pearl.level();

        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) return;

        // Read the configuration first
        boolean shouldLoadChunks = ModConfig.loadChunks();

        // If chunk loading is enabled generally, we always check who threw it
        if (shouldLoadChunks) {

            String ownerName = null;
            boolean isOwnerOffline = true;
            UUID ownerId = null;

            // Get the UUID of the player who threw it
            if (pearl.getOwner() != null) {
                ownerId = pearl.getOwner().getUUID();
            } else {
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                pearl.saveWithoutId(tag);
                if (tag.hasUUID("Owner")) {
                    ownerId = tag.getUUID("Owner");
                }
            }

            // Extract player and name
            if (ownerId != null) {
                ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(ownerId);
                if (player != null) {
                    isOwnerOffline = false;
                    ownerName = player.getScoreboardName();
                } else {
                    // Extract their name from the server cache if they logged out
                    ownerName = serverLevel.getServer().getProfileCache().get(ownerId).map(GameProfile::getName).orElse(null);
                }
            }

            if (isOwnerOffline && !ModConfig.loadChunksWhileOwnerIsGone()) {
                shouldLoadChunks = false;
            }
        }

        if (shouldLoadChunks) {
            ChunkPos currentPos = pearl.chunkPosition();

            if (this.loadedChunkPos == null || !this.loadedChunkPos.equals(currentPos)) {
                serverLevel.setChunkForced(currentPos.x, currentPos.z, true);

                if (this.loadedChunkPos != null) {
                    this.safelyUnforceChunk(serverLevel, this.loadedChunkPos, pearl);
                }
                this.loadedChunkPos = currentPos;
            }
        } else {
            if (this.loadedChunkPos != null) {
                this.safelyUnforceChunk(serverLevel, this.loadedChunkPos, pearl);
                this.loadedChunkPos = null;
            }
        }
    }

    @Inject(method = "onHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ThrownEnderpearl;getOwner()Lnet/minecraft/world/entity/Entity;"), cancellable = true)
    private void forceCrossDimensionalTeleport(HitResult result, CallbackInfo ci) {
        ThrownEnderpearl pearl = (ThrownEnderpearl) (Object) this;
        Level level = pearl.level();

        // Safety check to ensure we are on the server
        if (level.isClientSide() || !(level instanceof ServerLevel pearlLevel)) return;

        // UUID extraction
        UUID ownerId = null;

        if (pearl.getOwner() != null) {
            ownerId = pearl.getOwner().getUUID();
        } else {
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            pearl.saveWithoutId(tag);
            if (tag.hasUUID("Owner")) {
                ownerId = tag.getUUID("Owner");
            }
        }

        if (ownerId != null) {
            // Find the player anywhere on the server, even if their entity was recreated
            ServerPlayer player = pearlLevel.getServer().getPlayerList().getPlayer(ownerId);

            if (player != null) {
                ServerLevel playerLevel = (ServerLevel) player.level();

                // If the player and pearl are in different dimensions
                if (pearlLevel != playerLevel && !player.isSleeping()) {

                    // If cross-dimensional teleport is disabled, destroy the pearl
                    if (!ModConfig.canTeleportBetweenDimensions()) {
                        pearl.discard();
                        ci.cancel();
                        return;
                    }

                    // Safely knock the player off any vehicle before teleportating them between dimensions
                    if (player.isPassenger()) {
                        player.stopRiding();
                    }

                    // The cross dimensional teleport
                    player.teleportTo(pearlLevel, pearl.getX(), pearl.getY(), pearl.getZ(), player.getYRot(), player.getXRot());

                    // Flag the player
                    ((PearlTransitionAccessor) player).setPendingPearlEffect(true);

                    // Delete the pearl and cancel the vanilla code so it doesn't fail
                    pearl.discard();
                    ci.cancel();
                }
            }
        }
    }

    // Stop the pearl from destroying itself when you die
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isAlive()Z"))
    private boolean bypassDeathDiscard(Entity owner) {
        if (ModConfig.destroyAtDeath()) {
            return owner.isAlive();
        }
        return true;
    }
}