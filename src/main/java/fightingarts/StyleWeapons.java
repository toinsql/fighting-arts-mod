package fightingarts;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;
import java.util.function.Supplier;

public final class StyleWeapons extends SimpleContainer implements INBTSerializable<CompoundTag> {
    public static final int SANTORYU_SLOTS = 3;
    public static final int EMPTY_SLOTS = 4;
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, FightingArts.MOD_ID);
    private static final Supplier<AttachmentType<StyleWeapons>> WEAPONS = TYPES.register("style_weapons",
            () -> AttachmentType.serializable(StyleWeapons::new).copyOnDeath().build());

    private StyleWeapons() {
        super(SANTORYU_SLOTS);
    }

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }

    public static StyleWeapons of(Player player) {
        return player.getData(WEAPONS);
    }

    public List<ItemStack> swords() {
        return getItems().stream().filter(stack -> stack.is(ItemTags.SWORDS)).map(ItemStack::copy).toList();
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        return ContainerHelper.saveAllItems(new CompoundTag(), getItems(), registries);
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag tag) {
        getItems().clear();
        ContainerHelper.loadAllItems(tag, getItems(), registries);
    }

    @SubscribeEvent
    public static void dropOnDeath(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        for (ItemStack stack : of(player).removeAllItems()) {
            if (!EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                event.getDrops().add(new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), stack));
            }
        }
    }
}
