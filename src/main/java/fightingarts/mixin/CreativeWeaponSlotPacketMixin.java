package fightingarts.mixin;

import fightingarts.StyleWeaponSlot;
import fightingarts.StyleWeapons;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class CreativeWeaponSlotPacketMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void fightingarts$setCreativeWeapon(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        int index = packet.slotNum();
        var menu = player.inventoryMenu;
        if (!menu.isValidSlotIndex(index) || index < 0 || !(menu.getSlot(index) instanceof StyleWeaponSlot slot)) return;

        ci.cancel();
        if (!player.gameMode.isCreative() || slot.position() >= StyleWeapons.SANTORYU_SLOTS) return;
        ItemStack item = packet.itemStack();
        if (item.isEmpty() || (item.getCount() == 1 && slot.mayPlace(item)
                && item.isItemEnabled(player.level().enabledFeatures()))) {
            slot.setByPlayer(item);
            menu.broadcastChanges();
        }
    }
}
