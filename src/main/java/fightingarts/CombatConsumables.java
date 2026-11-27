package fightingarts;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public final class CombatConsumables {
    private static final Map<UUID, Use> USES = new HashMap<>();
    private static final int HAND_SWITCH_TICKS = 4;

    private CombatConsumables() {}

    public static int firstSlot(Inventory inventory) {
        int blockedFood = -1;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            var food = stack.get(DataComponents.FOOD);
            if ((food == null && !stack.is(Items.POTION)) || stack.getUseDuration(inventory.player) <= 0) continue;
            if (food == null || inventory.player.canEat(food.canAlwaysEat())) return slot;
            if (blockedFood < 0) blockedFood = slot;
        }
        return blockedFood;
    }

    static void setHeld(ServerPlayer player, boolean held) {
        if (!held || !StyleService.inAttackMode(player)) {
            Use use = USES.remove(player.getUUID());
            if (use != null) stop(player, use, false);
        } else if (!USES.containsKey(player.getUUID())) {
            Use use = new Use(player.level());
            USES.put(player.getUUID(), use);
            long now = player.level().getGameTime();
            if (canUse(player, now)) start(player, use, player.getInventory(), now, true);
        }
    }

    private static boolean canUse(ServerPlayer player, long now) {
        StyleState style = StyleService.current(player);
        return style != null && style.phase().equals("ACTIVE") && player.isAlive() && !player.isSpectator()
                && !StyleService.isAttackLocked(style.phase(), style.combo(), now - style.comboStart())
                && !SantoryuSkills.busy(player) && !SantoryuSkills.visionActive(player) && !StyleDefense.guarding(player)
                && !player.isUsingItem() && player.containerMenu == player.inventoryMenu;
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Use use = USES.get(player.getUUID());
        if (use == null) return;
        if (!player.isAlive() || player.isSpectator() || use.level != player.level() || !StyleService.inAttackMode(player)) {
            setHeld(player, false);
            return;
        }
        long now = player.level().getGameTime();
        if (!canUse(player, now)) {
            stop(player, use, false);
            return;
        }
        Inventory inventory = player.getInventory();
        if (use.stack.isEmpty()) {
            if (now >= use.nextStart) start(player, use, inventory, now, false);
            return;
        }
        if (inventory.getItem(use.slot) != use.stack) {
            stop(player, use, false);
            return;
        }
        if (now == use.start) return;
        use.remaining = EventHooks.onItemUseTick(player, use.stack, use.remaining);
        if (use.remaining > 0) {
            use.stack.onUseTick(player.level(), player, use.remaining);
            if (now - use.start >= use.duration / 5 && use.remaining % 4 == 0) playUseEffects(player, use.stack);
        }
        if (--use.remaining > 0) return;

        ItemStack before = use.stack.copy();
        playUseEffects(player, before);
        ItemStack result = use.stack.finishUsingItem(player.level(), player);
        result = EventHooks.onItemUseFinish(player, before, 0, result);
        if (inventory.getItem(use.slot) == use.stack) inventory.setItem(use.slot, result);
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        stop(player, use, true);
    }

    private static void start(ServerPlayer player, Use use, Inventory inventory, long now, boolean feedback) {
        int slot = firstSlot(inventory);
        if (slot < 0) return;
        ItemStack stack = inventory.getItem(slot);
        var food = stack.get(DataComponents.FOOD);
        if (food != null && !player.canEat(food.canAlwaysEat())) {
            if (feedback) player.displayClientMessage(Component.translatable("message.fightingarts.full_for_food"), true);
            return;
        }
        // Use the stack in its own slot so neither hand nor the selected hotbar slot changes on the server.
        int duration = Math.max(1, Math.round(stack.getUseDuration(player) * .7f));
        duration = EventHooks.onItemUseStart(player, stack, InteractionHand.OFF_HAND, duration);
        if (duration <= 0) {
            use.nextStart = now + HAND_SWITCH_TICKS;
            return;
        }
        use.slot = slot;
        use.stack = stack;
        use.start = now;
        use.duration = use.remaining = duration;
        StyleNetwork.broadcastConsumable(player, snapshot(player, stack.copy(), now, duration));
    }

    private static void stop(ServerPlayer player, Use use, boolean finished) {
        if (use.duration == 0) return;
        if (!finished && !use.stack.isEmpty() && !EventHooks.onUseItemStop(player, use.stack, use.remaining)) {
            use.stack.releaseUsing(player.level(), player, use.remaining);
        }
        use.stack = ItemStack.EMPTY;
        use.duration = 0;
        long now = player.level().getGameTime();
        use.nextStart = now + HAND_SWITCH_TICKS;
        StyleNetwork.broadcastConsumable(player, snapshot(player, ItemStack.EMPTY, now, 0));
    }

    private static void playUseEffects(ServerPlayer player, ItemStack stack) {
        var random = player.getRandom();
        if (stack.getUseAnimation() == UseAnim.DRINK) {
            playSound(player, stack.getDrinkingSound(), .9f + random.nextFloat() * .1f);
        } else if (stack.getUseAnimation() == UseAnim.EAT) {
            playSound(player, stack.getEatingSound(), 1f + (random.nextFloat() - random.nextFloat()) * .2f);
            var mouth = player.getEyePosition().add(player.getLookAngle().scale(.55)).add(0, -.43, 0);
            player.serverLevel().sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stack.copyWithCount(1)),
                    mouth.x, mouth.y, mouth.z, 5, .10, .10, .07, .03);
        }
    }

    private static void playSound(ServerPlayer player, SoundEvent sound, float pitch) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, player.getSoundSource(), .5f, pitch);
    }

    @SubscribeEvent
    public static void startTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer watcher) || !(event.getTarget() instanceof ServerPlayer player)) return;
        Use use = USES.get(player.getUUID());
        if (use != null && !use.stack.isEmpty()) {
            StyleNetwork.sendConsumable(watcher, snapshot(player, use.stack.copy(), use.start, use.duration));
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        USES.remove(event.getEntity().getUUID());
    }

    private static Snapshot snapshot(Player player, ItemStack stack, long tick, int duration) {
        return new Snapshot(player.getUUID(), stack, tick, duration);
    }

    public record Snapshot(UUID player, ItemStack stack, long tick, int duration) {}

    private static final class Use {
        private final Level level;
        private ItemStack stack = ItemStack.EMPTY;
        private long start, nextStart;
        private int slot, duration, remaining;

        private Use(Level level) {
            this.level = level;
        }
    }
}
