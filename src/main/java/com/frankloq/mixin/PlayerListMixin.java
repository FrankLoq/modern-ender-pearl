package com.frankloq.mixin;

import com.frankloq.ModConfig;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void onTrolledPlayerJoin(Connection connection, ServerPlayer player, CallbackInfo ci) {

        if (ModConfig.INSTANCE.trolledPlayers.contains(player.getScoreboardName())) {

            String rawMessage = ModConfig.INSTANCE.trollJoinMessage.replace("%player%", player.getScoreboardName());
            rawMessage = rawMessage.replace('&', '§');
            Component message = Component.literal(rawMessage);

            if (ModConfig.INSTANCE.broadcastTrollMessageToEveryone) {
                ((PlayerList) (Object) this).broadcastSystemMessage(message, false);
            } else {
                player.sendSystemMessage(message);
            }
        }
    }
}