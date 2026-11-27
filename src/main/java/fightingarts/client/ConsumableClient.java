package fightingarts.client;

import fightingarts.CombatConsumables;
import fightingarts.StyleNetwork;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

public final class ConsumableClient {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final ResourceLocation SLOT = ResourceLocation.withDefaultNamespace("container/slot");
    private static final Map<UUID, Use> USES = new HashMap<>();
    private static ClientLevel level;
    private static boolean sentHeld;
    private static boolean windowActive;

    private ConsumableClient() {}

    static void install() {
        NeoForge.EVENT_BUS.addListener(ConsumableClient::key);
        StyleNetwork.clientConsumableReceiver = snapshot -> {
            updateWorld();
            if (!snapshot.stack().isEmpty()) {
                USES.put(snapshot.player(), new Use(snapshot));
            } else {
                Use use = USES.get(snapshot.player());
                if (use != null) use.stop = snapshot.tick();
            }
        };
    }

    private static void updateWorld() {
        if (MC.level == level) return;
        level = MC.level;
        USES.clear();
        sentHeld = false;
    }

    private static void key(InputEvent.Key event) {
        if (event.getKey() != GLFW.GLFW_KEY_Q || event.getAction() == GLFW.GLFW_REPEAT) return;
        updateWorld();
        if (event.getAction() == GLFW.GLFW_RELEASE) sendHeld(false);
        else if (canUseKey()) sendHeld(true);
    }

    private static boolean canUseKey() {
        return level != null && MC.screen == null && MC.getOverlay() == null && MC.player != null
                && MC.player.isAlive() && !MC.player.isSpectator() && StyleClient.attackMode();
    }

    private static void sendHeld(boolean held) {
        if (held == sentHeld || MC.getConnection() == null) return;
        sentHeld = held;
        StyleNetwork.requestConsumable(held);
    }

    static void tick() {
        updateWorld();
        boolean focused = MC.isWindowActive();
        if (!canUseKey() || windowActive && !focused) sendHeld(false);
        windowActive = focused;
        if (level == null) return;
        long now = level.getGameTime();
        USES.values().removeIf(use -> now >= use.end() + 4);
    }

    static Frame frame(Player player, float partialTick) {
        Use use = USES.get(player.getUUID());
        if (use == null) return null;
        double now = player.level().getGameTime() + (double) partialTick;
        double elapsed = Math.max(0, now - use.snapshot.tick());
        double stopAge = Math.max(0, now - use.end());
        double playingTime = Math.max(0, Math.min(elapsed, use.end() - use.snapshot.tick()));
        float draw = (float)(AttackHotbar.ease(playingTime / 2) * (1 - AttackHotbar.ease(stopAge / 4)));
        float progress = (float)Math.clamp(playingTime / use.snapshot.duration(), 0, 1);
        return new Frame(use.snapshot.stack(), draw, progress, now < use.end());
    }

    public static int remainingForAnimation(Player player, float partialTick, int vanillaRemaining) {
        Frame frame = frame(player, partialTick);
        return player != MC.player || frame == null || player.isUsingItem() ? vanillaRemaining
                : Math.max(0, Math.round((1 - frame.progress()) * frame.stack().getUseDuration(player)));
    }

    public static float chewPhase(Player player, float partialTick, float vanillaPhase) {
        if (player != MC.player || player.isUsingItem()) return vanillaPhase;
        Use use = USES.get(player.getUUID());
        if (use == null || use.snapshot.stack().getUseAnimation() != UseAnim.EAT) return vanillaPhase;
        // Vanilla takes abs(cos(phase)). A half-turn every six game ticks only changes the visual bob.
        float elapsed = frame(player, partialTick).progress() * use.snapshot.duration();
        return elapsed * ((float)Math.PI / 6);
    }

    static void renderSlot(GuiGraphics graphics, int panelX, int panelTop, float partialTick) {
        int slot = CombatConsumables.firstSlot(MC.player.getInventory());
        Frame frame = frame(MC.player, partialTick);
        boolean using = frame != null && frame.active();
        if (slot < 0 && !using) return;
        ItemStack stack = using ? frame.stack() : MC.player.getInventory().getItem(slot);
        var key = Component.literal("Q");
        int keyWidth = MC.font.width(key);
        int size = Math.clamp(panelTop - 3, 11, 18);
        int x = panelX + (64 - size) / 2;
        int y = panelTop - size - 2;
        int keyX = x + size + 3;
        if (y < 1) {
            size = 18;
            x = panelX - size - keyWidth - 7;
            y = Math.max(2, panelTop + 3);
            keyX = x + size + 3;
        } else if (y < 26) {
            long effects = MC.player.getActiveEffects().stream().filter(effect -> effect.getEffect().value().isBeneficial()).count();
            x = Math.max(keyWidth + 4, Math.min(x, graphics.guiWidth() - 4 - 25 * (int)effects - size));
            keyX = x - keyWidth - 3;
        }
        graphics.blitSprite(SLOT, x, y, size, size);
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x + 1, y + 1, 0);
        float itemScale = (size - 2) / 16f;
        pose.scale(itemScale, itemScale, 1);
        graphics.renderItem(stack, 0, 0);
        graphics.renderItemDecorations(MC.font, stack, 0, 0);
        pose.popPose();
        graphics.drawString(MC.font, key, keyX, y + (size - 8) / 2, 0xfff3d394);
        if (using) {
            int bottom = y + size;
            graphics.fill(x + 1, bottom - 1, x + size - 1, bottom, 0xff211b26);
            graphics.fill(x + 1, bottom - 1, x + 1 + Math.round((size - 2) * frame.progress()), bottom, 0xff91edaf);
        }
    }

    record Frame(ItemStack stack, float draw, float progress, boolean active) {}

    private static final class Use {
        private final CombatConsumables.Snapshot snapshot;
        private long stop = Long.MAX_VALUE;

        private Use(CombatConsumables.Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        private long end() {
            return Math.min(stop, snapshot.tick() + snapshot.duration());
        }
    }
}
