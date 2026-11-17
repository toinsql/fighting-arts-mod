package fightingarts.client;

import fightingarts.StyleWeaponSlot;
import fightingarts.StyleWeapons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;

final class WeaponSlotsHud {
    private static final ResourceLocation SLOT = ResourceLocation.withDefaultNamespace("container/slot");

    private WeaponSlotsHud() {}

    static void render(ContainerScreenEvent.Render.Background event) {
        var screen = event.getContainerScreen();
        if (!(screen instanceof InventoryScreen) && !(screen instanceof CreativeModeInventoryScreen)) return;

        var player = Minecraft.getInstance().player;
        if (player == null) return;
        boolean creative = screen instanceof CreativeModeInventoryScreen;
        int count = StyleWeapons.of(player).isEmpty() ? StyleWeapons.EMPTY_SLOTS : StyleWeapons.SANTORYU_SLOTS;
        int left = screen.getGuiLeft() + (creative ? StyleWeaponSlot.CREATIVE_X : StyleWeaponSlot.SURVIVAL_X) - 1;
        int top = screen.getGuiTop() + (creative ? StyleWeaponSlot.CREATIVE_Y : StyleWeaponSlot.SURVIVAL_Y) - 1;
        int size = StyleWeaponSlot.SPACING;
        Slot second = screen.getMenu().slots.stream()
                .filter(slot -> slot.container instanceof StyleWeapons && slot.getSlotIndex() == 1)
                .findFirst().orElse(null);
        if (second == null) return;

        boolean expanded = event.getMouseX() >= left && event.getMouseX() < left + size * count
                && event.getMouseY() >= top && event.getMouseY() < top + size
                && (second.isActive() || event.getMouseX() < left + size);
        for (Slot slot : player.inventoryMenu.slots) {
            if (!(slot instanceof StyleWeaponSlot weapon)) continue;
            int position = weapon.position();
            weapon.setVisible(position == 0 || (expanded && position < count));
            if (weapon.isActive()) {
                event.getGuiGraphics().blitSprite(SLOT, left + size * position, top, size, size);
            }
        }
    }
}
