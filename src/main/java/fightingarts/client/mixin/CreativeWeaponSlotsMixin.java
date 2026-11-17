package fightingarts.client.mixin;

import fightingarts.StyleWeaponSlot;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(CreativeModeInventoryScreen.class)
abstract class CreativeWeaponSlotsMixin {
    @ModifyArgs(method = "selectTab", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen$SlotWrapper;<init>(Lnet/minecraft/world/inventory/Slot;III)V"))
    private void fightingarts$placeCreativeWeaponSlots(Args args) {
        if ((Slot) args.get(0) instanceof StyleWeaponSlot slot) {
            args.set(2, StyleWeaponSlot.CREATIVE_X + slot.position() * StyleWeaponSlot.SPACING);
            args.set(3, StyleWeaponSlot.CREATIVE_Y);
        }
    }
}
