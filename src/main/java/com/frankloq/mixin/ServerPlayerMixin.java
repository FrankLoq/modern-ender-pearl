package com.frankloq.mixin;

import com.frankloq.access.PearlTransitionAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin implements PearlTransitionAccessor {

    @Unique
    private boolean pendingPearlEffect = false;

    @Unique
    private double pearlStartX, pearlStartY, pearlStartZ;
    @Unique
    private float pearlStartXRot, pearlStartYRot;

    @Override
    public void setPendingPearlEffect(boolean pending) {
        this.pendingPearlEffect = pending;

        if (pending) {
            ServerPlayer player = (ServerPlayer) (Object) this;

            // We bump the player 0.05 blocks into the air
            // The exact millisecond their loading screen vanishes, client gravity takes over.
            // They will instantly fall 0.05 blocks and trigger the movement check
            player.setPos(player.getX(), player.getY() + 0.05, player.getZ());

            this.pearlStartX = player.getX();
            this.pearlStartY = player.getY();
            this.pearlStartZ = player.getZ();
            this.pearlStartXRot = player.getXRot();
            this.pearlStartYRot = player.getYRot();
        }
    }

    @Override
    public boolean hasPendingPearlEffect() {
        return this.pendingPearlEffect;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void tickPearlTransition(CallbackInfo ci) {
        if (this.pendingPearlEffect) {
            ServerPlayer player = (ServerPlayer) (Object) this;

            double distSqr = (player.getX() - this.pearlStartX) * (player.getX() - this.pearlStartX) +
                    (player.getY() - this.pearlStartY) * (player.getY() - this.pearlStartY) +
                    (player.getZ() - this.pearlStartZ) * (player.getZ() - this.pearlStartZ);

            boolean hasMoved = distSqr > 0.0001;
            boolean hasLooked = Math.abs(player.getXRot() - this.pearlStartXRot) > 0.1f || Math.abs(player.getYRot() - this.pearlStartYRot) > 0.1f;

            if (hasMoved || hasLooked) {
                this.pendingPearlEffect = false;

                ServerLevel targetLevel = (ServerLevel) player.level();

                // Remove spawn invulnerability
                player.invulnerableTime = 0;
                if (player instanceof ServerPlayerAccessor accessor) {
                    accessor.setSpawnInvulnerableTime(0);
                }

                // Deal damage
                player.resetFallDistance();
                player.hurt(targetLevel.damageSources().fall(), 5.0F);

                // Restore the spawn invulnerability
                if (player instanceof ServerPlayerAccessor accessor) {
                    accessor.setSpawnInvulnerableTime(60);
                }

                // Endermite spawning
                if (targetLevel.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) && targetLevel.random.nextFloat() < 0.05F) {
                    Endermite endermite = EntityType.ENDERMITE.create(targetLevel);
                    if (endermite != null) {
                        endermite.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
                        targetLevel.addFreshEntity(endermite);
                    }
                }
            }
        }
    }
}