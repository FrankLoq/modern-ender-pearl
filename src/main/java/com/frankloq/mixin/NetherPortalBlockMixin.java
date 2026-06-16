package com.frankloq.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void instantMountedTeleport(BlockState state, Level level, BlockPos pos, Entity entity, CallbackInfo ci) {
        if (level.isClientSide()) return;

        if (!com.frankloq.ModConfig.INSTANCE.canVehicleWithPassengersCrossPortals) return;

        // Create mod compatibility
        if (com.frankloq.compat.CreateModCompat.isCreateContraption(entity.getRootVehicle())) {
            return;
        }

        // If any entity touches the portal while on cooldown, freeze their timer at 10 ticks
        // so they don't teleport back and forth if they stay in the portal
        if (entity.isOnPortalCooldown()) {
            ((EntityCooldownAccessor) entity).setPortalCooldownValue(10);
            ci.cancel();
            return;
        }

        Entity tempVehicle = entity.getRootVehicle();

        if (!tempVehicle.isVehicle()) {
            return;
        }

        // Teleport EVERYTHING that is a riding the vehicle
        List<Entity> entireTree = new ArrayList<>();
        entireTree.add(tempVehicle);
        for (int i = 0; i < entireTree.size(); i++) {
            entireTree.addAll(entireTree.get(i).getPassengers());
        }

        // Troll check
        for (Entity e : entireTree) {
            if (e instanceof ServerPlayer sp) {
                if (com.frankloq.ModConfig.INSTANCE.trolledPlayers.contains(sp.getScoreboardName())) {
                    return;
                }
            }
        }

        // If anyone riding a vehicle is on cooldown, put everyone on cooldown
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
        ServerLevel targetLevel = currentLevel.getServer().getLevel(
                currentLevel.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER
        );

        if (targetLevel != null) {
            for (Entity e : entireTree) {
                ((EntityCooldownAccessor) e).setPortalCooldownValue(10);
            }

            final List<Entity> finalTree = entireTree;
            final ServerLevel finalTargetLevel = targetLevel;

            currentLevel.getServer().execute(() -> {

                // Validate everyone before teleporting
                List<Entity> validTree = new ArrayList<>();
                for (Entity e : finalTree) {
                    if (e.isAlive() && !e.isRemoved()) {
                        EntityAccessor access = (EntityAccessor) e;
                        if (access.getPortalEntrancePos() == null) {
                            access.setPortalEntrancePos(e.blockPosition());
                        }
                        validTree.add(e);
                    }
                }

                if (validTree.isEmpty()) return;

                // Memorize exactly who is riding who before ripping them apart
                Map<Entity, Entity> rideMap = new HashMap<>();
                for (Entity e : validTree) {
                    rideMap.put(e, e.getVehicle());
                    e.stopRiding();
                }

                // Find a player to safely generate the portal (fallback to the boat if no players)
                Entity anchorOld = null;
                for (Entity e : validTree) {
                    if (e instanceof ServerPlayer) {
                        anchorOld = e;
                        break;
                    }
                }
                if (anchorOld == null) anchorOld = validTree.get(0);

                Map<Entity, Entity> migratedMap = new HashMap<>();

                // Send the player through to generate the portal
                Entity anchorNew = anchorOld.changeDimension(finalTargetLevel);

                if (anchorNew != null) {
                    migratedMap.put(anchorOld, anchorNew);

                    // Find the exact center of the newly generated portal
                    double safeX = anchorNew.getX();
                    double safeY = anchorNew.getY();
                    double safeZ = anchorNew.getZ();

                    BlockPos newPos = anchorNew.blockPosition();
                    BlockState newState = finalTargetLevel.getBlockState(newPos);

                    if (newState.getBlock() instanceof NetherPortalBlock) {
                        Direction.Axis axis = newState.getValue(NetherPortalBlock.AXIS);

                        if (axis == Direction.Axis.X) {
                            int minX = newPos.getX();
                            while (finalTargetLevel.getBlockState(new BlockPos(minX - 1, newPos.getY(), newPos.getZ())).getBlock() instanceof NetherPortalBlock) {
                                minX--;
                            }
                            int maxX = newPos.getX();
                            while (finalTargetLevel.getBlockState(new BlockPos(maxX + 1, newPos.getY(), newPos.getZ())).getBlock() instanceof NetherPortalBlock) {
                                maxX++;
                            }
                            safeX = (minX + maxX + 1) / 2.0;
                            safeZ = Math.floor(safeZ) + 0.5;
                        } else if (axis == Direction.Axis.Z) {
                            int minZ = newPos.getZ();
                            while (finalTargetLevel.getBlockState(new BlockPos(newPos.getX(), newPos.getY(), minZ - 1)).getBlock() instanceof NetherPortalBlock) {
                                minZ--;
                            }
                            int maxZ = newPos.getZ();
                            while (finalTargetLevel.getBlockState(new BlockPos(newPos.getX(), newPos.getY(), maxZ + 1)).getBlock() instanceof NetherPortalBlock) {
                                maxZ++;
                            }
                            safeZ = (minZ + maxZ + 1) / 2.0;
                            safeX = Math.floor(safeX) + 0.5;
                        }
                    }

                    // Snap the player to the exact center
                    anchorNew.moveTo(safeX, safeY, safeZ, anchorNew.getYRot(), anchorNew.getXRot());

                    // Teleport everyone else into the center safely
                    for (Entity oldE : validTree) {
                        if (oldE == anchorOld) continue; // Skip the guy we already sent

                        Entity newE = null;

                        // Players must use native logic for networking. Anything else is safely cloned
                        if (oldE instanceof ServerPlayer sp) {
                            newE = sp.changeDimension(finalTargetLevel);
                            if (newE != null) {
                                newE.moveTo(safeX, safeY, safeZ, newE.getYRot(), newE.getXRot());
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

                    // Put everyone back in their exact seats
                    for (Entity oldE : validTree) {
                        Entity newE = migratedMap.get(oldE);
                        if (newE != null) {
                            // Apply the cooldown shield to every single entity
                            ((EntityCooldownAccessor) newE).setPortalCooldownValue(10);

                            // Check who they were riding in the Overworld, and mount the Nether equivalent
                            Entity oldVehicle = rideMap.get(oldE);
                            if (oldVehicle != null) {
                                Entity newVehicle = migratedMap.get(oldVehicle);
                                if (newVehicle != null) {
                                    newE.startRiding(newVehicle, true);
                                }
                            }
                        }
                    }
                }
            });

            ci.cancel();
        }
    }
}