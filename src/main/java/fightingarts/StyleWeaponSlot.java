package fightingarts;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class StyleWeaponSlot extends Slot {
    public static final int SPACING = 18;
    public static final int SURVIVAL_X = 77;
    public static final int SURVIVAL_Y = 44;
    public static final int CREATIVE_X = 125;
    public static final int CREATIVE_Y = 25;
    private final Player owner;
    private final int position;
    private final boolean extra;
    private boolean visible;

    public StyleWeaponSlot(Player owner, int position) {
        // The fourth position can receive the first sword. That sword belongs in slot zero.
        super(StyleWeapons.of(owner), position % StyleWeapons.SANTORYU_SLOTS,
                SURVIVAL_X + position * SPACING, SURVIVAL_Y);
        this.owner = owner;
        this.position = position;
        this.extra = position == StyleWeapons.SANTORYU_SLOTS;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) && (!extra || StyleWeapons.of(owner).isEmpty());
    }

    @Override
    public boolean isActive() {
        return visible && (!extra || StyleWeapons.of(owner).isEmpty());
    }

    @Override
    public ItemStack getItem() {
        return extra ? ItemStack.EMPTY : StyleWeapons.of(owner).getItem(getSlotIndex());
    }

    @Override
    public void set(ItemStack stack) {
        // Menu synchronization sends an empty stack for the fourth position.
        if (!extra || (!stack.isEmpty() && mayPlace(stack))) StyleWeapons.of(owner).setItem(getSlotIndex(), stack);
        setChanged();
    }

    @Override
    public ItemStack remove(int count) {
        return extra ? ItemStack.EMPTY : StyleWeapons.of(owner).removeItem(getSlotIndex(), count);
    }

    @Override
    public void setChanged() {
        StyleWeapons.of(owner).setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public int position() {
        return position;
    }
}
