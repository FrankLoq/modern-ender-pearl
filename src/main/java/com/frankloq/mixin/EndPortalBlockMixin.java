package com.frankloq.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void instantMountedEndTeleport(BlockState state, Level level, BlockPos pos, Entity entity, CallbackInfo ci) {
        if (level.isClientSide()) return;

        // Ignore the player hitbox if it's a passenger so it doesn't feel off when entering the portal in a vehicle
        if (entity.isPassenger()) {
            ci.cancel();
            return;
        }

        // And only check if the vehicle is the one touching the portal
        if (!entity.isVehicle()) {
            return;
        }

        // Teleport EVERYTHING that is a riding the vehicle
        List<Entity> entireTree = new ArrayList<>();
        entireTree.add(entity);
        for (int i = 0; i < entireTree.size(); i++) {
            entireTree.addAll(entireTree.get(i).getPassengers());
        }

        boolean onCooldown = false;
        for (Entity e : entireTree) {
            if (e.isOnPortalCooldown()) {
                onCooldown = true;
                break;
            }
        }

        if (onCooldown) {
            for (Entity e : entireTree) {
                ((EntityCooldownAccessor) e).setPortalCooldownValue(10);
            }
            ci.cancel();
            return;
        }

        ServerLevel currentLevel = (ServerLevel) level;
        ServerLevel targetLevel = null;

        Entity tempAnchor = null;
        for (Entity e : entireTree) {
            if (e instanceof ServerPlayer) {
                tempAnchor = e;
                break;
            }
        }
        if (tempAnchor == null) tempAnchor = entireTree.get(0);

        if (currentLevel.dimension() == Level.END) {
            if (tempAnchor instanceof ServerPlayer sp) {
                ServerLevel respawnLevel = currentLevel.getServer().getLevel(sp.getRespawnDimension());
                targetLevel = (respawnLevel != null) ? respawnLevel : currentLevel.getServer().getLevel(Level.OVERWORLD);
            } else {
                targetLevel = currentLevel.getServer().getLevel(Level.OVERWORLD);
            }
        } else {
            targetLevel = currentLevel.getServer().getLevel(Level.END);
        }

        if (targetLevel != null) {
            for (Entity e : entireTree) {
                ((EntityCooldownAccessor) e).setPortalCooldownValue(10);
            }

            final List<Entity> finalTree = entireTree;
            final ServerLevel finalTargetLevel = targetLevel;

            currentLevel.getServer().execute(() -> {

                List<Entity> validTree = new ArrayList<>();
                for (Entity e : finalTree) {
                    if (e.isAlive() && !e.isRemoved()) validTree.add(e);
                }

                if (validTree.isEmpty()) return;

                Map<Entity, Entity> rideMap = new HashMap<>();
                for (Entity e : validTree) {
                    rideMap.put(e, e.getVehicle());
                    e.stopRiding();
                }

                Entity anchorOld = null;
                for (Entity e : validTree) {
                    if (e instanceof ServerPlayer) {
                        anchorOld = e;
                        break;
                    }
                }
                if (anchorOld == null) anchorOld = validTree.get(0);

                double safeX = 0, safeY = 0, safeZ = 0;
                boolean precalculated = false;

                if (currentLevel.dimension() == Level.END && anchorOld instanceof ServerPlayer sp) {
                    BlockPos respawnPos = sp.getRespawnPosition();
                    float respawnAngle = sp.getRespawnAngle();
                    boolean forced = sp.isRespawnForced();

                    if (respawnPos != null) {
                        Optional<Vec3> opt = Player.findRespawnPositionAndUseSpawnBlock(
                                finalTargetLevel, respawnPos, respawnAngle, forced, true
                        );

                        if (opt.isPresent()) {
                            safeX = opt.get().x;
                            safeY = opt.get().y + 1.0;
                            safeZ = opt.get().z;
                            precalculated = true;
                        }
                    }

                    if (!precalculated) {
                        BlockPos spawnBlock = finalTargetLevel.getSharedSpawnPos();
                        safeX = spawnBlock.getX() + 0.5;
                        safeY = finalTargetLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnBlock.getX(), spawnBlock.getZ()) + 1.0;
                        safeZ = spawnBlock.getZ() + 0.5;
                        precalculated = true;
                    }
                }

                Map<Entity, Entity> migratedMap = new HashMap<>();

                if (!precalculated) {
                    Entity anchorNew = anchorOld.changeDimension(finalTargetLevel);
                    if (anchorNew != null) {
                        safeX = anchorNew.getX();
                        safeY = anchorNew.getY();
                        safeZ = anchorNew.getZ();
                        migratedMap.put(anchorOld, anchorNew);
                    } else {
                        BlockPos spawnBlock = finalTargetLevel.getSharedSpawnPos();
                        safeX = spawnBlock.getX() + 0.5;
                        safeY = finalTargetLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnBlock.getX(), spawnBlock.getZ());
                        safeZ = spawnBlock.getZ() + 0.5;
                    }
                } else {
                    if (anchorOld instanceof ServerPlayer sp) {
                        sp.teleportTo(finalTargetLevel, safeX, safeY, safeZ, sp.getYRot(), sp.getXRot());
                        migratedMap.put(anchorOld, anchorOld);
                    } else {
                        Entity newAnchor = anchorOld.changeDimension(finalTargetLevel);
                        if (newAnchor != null) {
                            newAnchor.moveTo(safeX, safeY, safeZ, newAnchor.getYRot(), newAnchor.getXRot());
                            migratedMap.put(anchorOld, newAnchor);
                        }
                    }
                }

                for (Entity oldE : validTree) {
                    if (oldE == anchorOld) continue;

                    Entity newE = null;
                    if (oldE instanceof ServerPlayer sp) {
                        if (precalculated) {
                            sp.teleportTo(finalTargetLevel, safeX, safeY, safeZ, sp.getYRot(), sp.getXRot());
                            newE = sp;
                        } else {
                            newE = sp.changeDimension(finalTargetLevel);
                            if (newE != null) newE.moveTo(safeX, safeY, safeZ, newE.getYRot(), newE.getXRot());
                        }
                    } else {
                        newE = oldE.getType().create(finalTargetLevel);
                        if (newE != null) {
                            newE.restoreFrom(oldE);
                            newE.setUUID(UUID.randomUUID());
                            newE.moveTo(safeX, safeY, safeZ, oldE.getYRot(), oldE.getXRot());

                            if (oldE instanceof LivingEntity livingOld && newE instanceof LivingEntity livingNew) {
                                livingNew.setYHeadRot(livingOld.getYHeadRot());
                            }

                            oldE.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
                            finalTargetLevel.addFreshEntity(newE);
                        }
                    }

                    if (newE != null) {
                        migratedMap.put(oldE, newE);
                    }
                }

                for (Entity oldE : validTree) {
                    Entity newE = migratedMap.get(oldE);
                    if (newE != null) {
                        ((EntityCooldownAccessor) newE).setPortalCooldownValue(10);

                        Entity oldVehicle = rideMap.get(oldE);
                        if (oldVehicle != null) {
                            Entity newVehicle = migratedMap.get(oldVehicle);
                            if (newVehicle != null) {
                                newE.startRiding(newVehicle, true);
                            }
                        }
                    }
                }
            });

            ci.cancel();
        }
    }
}