package fightingarts.mixin;

import fightingarts.StyleWeaponSlot;
import fightingarts.StyleWeapons;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryMenu.class)
abstract class InventoryMenuMixin extends AbstractContainerMenu {
    protected InventoryMenuMixin(MenuType<?> type, int id) {
        super(type, id);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fightingarts$addWeaponSlots(Inventory inventory, boolean server, Player player, CallbackInfo ci) {
        for (int i = 0; i < StyleWeapons.EMPTY_SLOTS; i++) {
            addSlot(new StyleWeaponSlot(player, i));
        }
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return !(slot instanceof StyleWeaponSlot weapon && weapon.position() == StyleWeapons.SANTORYU_SLOTS)
                && super.canDragTo(slot);
    }
}
