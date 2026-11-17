package fightingarts.mixin;

import fightingarts.CombatRestrictions;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class CombatDropMixin {
    @Shadow public ServerPlayer player;
    @Inject(method="handlePlayerAction",at=@At("HEAD"),cancellable=true)
    private void fightingarts$drop(ServerboundPlayerActionPacket packet,CallbackInfo callback) {
        net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet,(ServerGamePacketListenerImpl)(Object)this,player.serverLevel());
        if(CombatRestrictions.active(player) && (packet.getAction()==ServerboundPlayerActionPacket.Action.DROP_ITEM
                || packet.getAction()==ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS)) {
            callback.cancel();player.containerMenu.sendAllDataToRemote();
        }
    }
    @Inject(method="handleContainerClick",at=@At("HEAD"),cancellable=true)
    private void fightingarts$inventoryDrop(ServerboundContainerClickPacket packet,CallbackInfo callback) {
        if(CombatRestrictions.active(player) && (packet.getClickType()==ClickType.THROW
                || packet.getSlotNum()==-999 && packet.getClickType()==ClickType.PICKUP)) {
            callback.cancel();player.containerMenu.sendAllDataToRemote();
        }
    }
}
