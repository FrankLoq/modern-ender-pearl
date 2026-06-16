package com.frankloq.mixin;

import com.frankloq.access.PearlTransitionAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlMixin {

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void interceptPortalFlight(CallbackInfo ci) {
		ThrownEnderpearl pearl = (ThrownEnderpearl) (Object) this;

		if (pearl.level().isClientSide() || pearl.isRemoved() || pearl.isOnPortalCooldown() || !com.frankloq.ModConfig.INSTANCE.canGoThroughPortals) return;

		net.minecraft.world.phys.Vec3 start = pearl.position();
		net.minecraft.world.phys.Vec3 defaultEnd = start.add(pearl.getDeltaMovement());

		// The block raytrace
		net.minecraft.world.phys.HitResult hitResult = pearl.level().clip(new net.minecraft.world.level.ClipContext(
				start,
				defaultEnd,
				net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE,
				pearl
		));

		net.minecraft.world.phys.Vec3 end = hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS
				? hitResult.getLocation()
				: defaultEnd;

		// Create the precise bounding box
		net.minecraft.world.phys.AABB flightBox = pearl.getBoundingBox().expandTowards(end.subtract(start));

		Iterable<BlockPos> positions = BlockPos.betweenClosed(
				(int) Math.floor(flightBox.minX), (int) Math.floor(flightBox.minY), (int) Math.floor(flightBox.minZ),
				(int) Math.floor(flightBox.maxX), (int) Math.floor(flightBox.maxY), (int) Math.floor(flightBox.maxZ)
		);

		for (BlockPos pos : positions) {
			BlockState state = pearl.level().getBlockState(pos);

			if (state.getBlock() instanceof NetherPortalBlock || state.getBlock() instanceof EndPortalBlock) {

				// Pane Check
				net.minecraft.world.phys.shapes.VoxelShape portalShape = state.getShape(pearl.level(), pos);

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

								// Troll check
								if (com.frankloq.ModConfig.INSTANCE.trolledPlayers.contains(player.getScoreboardName())) {
									return;
								}

								player.teleportTo(currentLevel, pearl.getX(), pearl.getY(), pearl.getZ(), player.getYRot(), player.getXRot());

								state.entityInside(currentLevel, pos, player);

								Entity migratedEntity = player.changeDimension(targetLevel);

								if (migratedEntity != null && migratedEntity instanceof ServerPlayer targetPlayer) {

									targetPlayer.setPortalCooldown();
									((PearlTransitionAccessor) targetPlayer).setPendingPearlEffect(true);
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
	private void safelyUnforceChunk(net.minecraft.server.level.ServerLevel serverLevel, net.minecraft.world.level.ChunkPos pos, ThrownEnderpearl currentPearl) {
		if (pos == null) return;

		// Calculate the exact boundaries of the Chunk (16x16 blocks)
		int startX = pos.x * 16;
		int startZ = pos.z * 16;
		int endX = startX + 16;
		int endZ = startZ + 16;

		// Draw a box from bedrock to the sky limit
		net.minecraft.world.phys.AABB chunkBox = new net.minecraft.world.phys.AABB(
				startX, serverLevel.getMinBuildHeight(), startZ,
				endX, serverLevel.getMaxBuildHeight(), endZ
		);

		// Scan the chunk for any other pearl that is not the current pearl
		java.util.List<ThrownEnderpearl> otherPearls = serverLevel.getEntitiesOfClass(
				ThrownEnderpearl.class,
				chunkBox,
				entity -> entity != currentPearl && entity.isAlive()
		);

		// If the chunk is completely empty of other pearls, it is safe to unload!
		if (otherPearls.isEmpty()) {
			serverLevel.setChunkForced(pos.x, pos.z, false);
		}
	}

	// The chunk loading logic
	@Unique
	private net.minecraft.world.level.ChunkPos loadedChunkPos = null;

	@Inject(method = "tick", at = @At("TAIL"))
	private void loadPearlChunks(CallbackInfo ci) {
		ThrownEnderpearl pearl = (ThrownEnderpearl) (Object) this;
		net.minecraft.world.level.Level level = pearl.level();

		if (level.isClientSide() || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
			return;
		}

		// Read the configuration first
		boolean shouldLoadChunks = com.frankloq.ModConfig.INSTANCE.loadChunks;

		// If chunk loading is enabled generally, we always check who threw it
		if (shouldLoadChunks) {

			String ownerName = null;
			boolean isOwnerOffline = true;
			java.util.UUID ownerId = null;

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
				net.minecraft.server.level.ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(ownerId);
				if (player != null) {
					isOwnerOffline = false;
					ownerName = player.getScoreboardName();
				} else {
					// Extract their name from the server cache if they logged out
					java.util.Optional<com.mojang.authlib.GameProfile> profile = serverLevel.getServer().getProfileCache().get(ownerId);
					if (profile.isPresent()) {
						ownerName = profile.get().getName();
					}
				}
			}

			// Troll check
			if (ownerName != null && com.frankloq.ModConfig.INSTANCE.trolledPlayers.contains(ownerName)) {
				shouldLoadChunks = false;
			}
			// Is player offline check
			else if (isOwnerOffline && !com.frankloq.ModConfig.INSTANCE.loadChunksWhileOwnerIsGone) {
				shouldLoadChunks = false;
			}
		}

		if (shouldLoadChunks) {
			net.minecraft.world.level.ChunkPos currentPos = pearl.chunkPosition();

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
		net.minecraft.world.level.Level level = pearl.level();

		// Safety check to ensure we are on the server
		if (level.isClientSide() || !(level instanceof net.minecraft.server.level.ServerLevel pearlLevel)) {
			return;
		}

		// UUID extraction
		java.util.UUID ownerId = null;

		if (pearl.getOwner() != null) {
			ownerId = pearl.getOwner().getUUID();
		} else {
			// If they left The End, the owner entity is null! Read the raw NBT data instead.
			net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
			pearl.saveWithoutId(tag);
			if (tag.hasUUID("Owner")) {
				ownerId = tag.getUUID("Owner");
			}
		}

		if (ownerId != null) {
			// Find the player anywhere on the server, even if their entity was recreated
			net.minecraft.server.level.ServerPlayer player = pearlLevel.getServer().getPlayerList().getPlayer(ownerId);

			if (player != null) {
				ServerLevel playerLevel = (ServerLevel) player.level();

				// If the player and pearl are in different dimensions
				if (pearlLevel != playerLevel && !player.isSleeping()) {

					if (!com.frankloq.ModConfig.INSTANCE.canTeleportBetweenDimensions) {
						return;
					}

					// Troll check
					if (com.frankloq.ModConfig.INSTANCE.trolledPlayers.contains(player.getScoreboardName())) {
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
		if (com.frankloq.ModConfig.INSTANCE.destroyAtDeath) {
			return owner.isAlive();
		}
		return true;
	}
}