package fightingarts.client;

import fightingarts.FightingArts;
import fightingarts.SantoryuSkills;
import fightingarts.StyleDefense;
import fightingarts.StyleMode;
import fightingarts.StyleService;
import fightingarts.StyleState;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

final class AttackHotbar {
    private static final ResourceLocation SLOT = ResourceLocation.withDefaultNamespace("container/slot");
    private static final ResourceLocation SYMBOL =
            ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID, "textures/gui/style_symbol.png");
    static final int SLIDE_TICKS = 7;
    private static final int SLIDE_DISTANCE = 70;
    private static final Set<ResourceLocation> STATUS_LAYERS = Set.of(
            VanillaGuiLayers.PLAYER_HEALTH, VanillaGuiLayers.ARMOR_LEVEL,
            VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.AIR_LEVEL, VanillaGuiLayers.VEHICLE_HEALTH,
            VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.JUMP_METER);
    private static long attackRise = Long.MIN_VALUE;
    private static long vanillaRise = Long.MIN_VALUE;
    private static double sheathOffset;

    private AttackHotbar() {}

    static void stateChanged(StyleState previous, StyleState next) {
        switch (next.phase()) {
            case StyleService.SIMPLE_DRAWING -> attackRise = next.phaseStart() + SLIDE_TICKS;
            case "ACTIVE" -> {
                if (previous == null || (!StyleService.SIMPLE_DRAWING.equals(previous.phase())
                        && !"ACTIVE".equals(previous.phase()))) attackRise = next.phaseStart();
            }
            case "OFF" -> {
                if (previous != null && !"OFF".equals(previous.phase())) vanillaRise = next.phaseStart();
            }
            case "SHEATHING" -> {
                if (previous != null && !"SHEATHING".equals(previous.phase())) {
                    sheathOffset = offset(previous, next.phaseStart());
                }
            }
        }
    }

    static void reset() {
        vanillaRise = Long.MIN_VALUE;
        attackRise = Long.MIN_VALUE;
        sheathOffset = 0;
    }

    static void render(RenderGuiLayerEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.player.isSpectator()) return;
        boolean hotbar = event.getName().equals(VanillaGuiLayers.HOTBAR);
        boolean itemName = event.getName().equals(VanillaGuiLayers.SELECTED_ITEM_NAME);
        boolean statusLayer = STATUS_LAYERS.contains(event.getName());
        if (!hotbar && !itemName && !statusLayer) return;

        StyleState state = StyleClient.local();
        String phase = state == null ? "OFF" : state.phase();
        double now = mc.level.getGameTime() + (double) event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double offset = offset(state, now);
        boolean off = phase.equals("OFF");
        if (off && offset == 0) return;
        boolean vanillaHotbar = off || phase.equals("DRAWING")
                || (phase.equals(StyleService.SIMPLE_DRAWING) && now < attackRise);

        var graphics = event.getGuiGraphics();
        if (statusLayer || hotbar && vanillaHotbar || itemName && off) {
            if (offset == 0) return;
            event.setCanceled(true);
            if (offset >= SLIDE_DISTANCE) return;
            graphics.pose().pushPose();
            graphics.pose().translate(0, offset, 0);
            try {
                event.getLayer().render(graphics, event.getPartialTick());
            } finally {
                graphics.pose().popPose();
            }
            return;
        }
        event.setCanceled(true);
        if (!hotbar || offset >= SLIDE_DISTANCE) return;
        int x = graphics.guiWidth() / 2 - 9;
        int y = graphics.guiHeight() - 20;
        graphics.pose().pushPose();
        graphics.pose().translate(0, offset, 0);
        SkillHud.sprite(graphics, 4, x - 82, y - 13, 84, 44);
        SkillHud.sprite(graphics, 5, x + 16, y - 13, 84, 44);
        if (state.mode().hasSkills() && !phase.equals("UPGRADING")) SkillHud.sprite(graphics, 6, x - 3, y - 43, 24, 54);
        if (state.mode() == StyleMode.AWAKENED) AwakeningEffects.hotbar(graphics, x, y, now);
        graphics.blitSprite(SLOT, x, y, 18, 18);
        graphics.blit(SYMBOL, x + 2, y + 2, 14, 14, 0, 0, 1254, 1254, 1254, 1254);
        var status = SkillHud.local();
        if (state.mode() == StyleMode.SIMPLE && status != null) {
            drawGuardCharges(graphics, x, y - 3, now, status);
            if (phase.equals("ACTIVE")) drawLeap(graphics, x + 100, y + 2, now, status);
        }
        graphics.pose().popPose();
    }

    private static void drawGuardCharges(GuiGraphics graphics, int x, int y, double now, SantoryuSkills.Status status) {
        for (int i = 0; i < SantoryuSkills.SIMPLE_CHARGES; i++) {
            int left = x + i * 4;
            graphics.fill(left, y, left + 2, y + 2, 0xff221e29);
            double remaining = status.guardReady(i) - now;
            if (remaining <= 0) {
                graphics.fill(left, y, left + 2, y + 1, 0xffe0f8dc);
                graphics.fill(left, y + 1, left + 2, y + 2, 0xff53ae80);
            } else {
                int shade = (int)(42 * Math.clamp(1 - remaining / StyleDefense.CHARGE_COOLDOWN_TICKS, 0, 1));
                graphics.fill(left, y + 1, left + 2, y + 2, 0xff4a3851 + shade * 0x010101);
            }
        }
    }

    private static void drawLeap(GuiGraphics graphics, int x, int y, double now, SantoryuSkills.Status status) {
        boolean available = SkillHud.simpleLeapReady(status, now);
        float tint = available ? 1 : .52f;
        graphics.setColor(tint, tint, tint, 1);
        SkillHud.skillIcon(graphics, 3, x, y, 14, 14);
        graphics.setColor(1, 1, 1, 1);
        var mc = Minecraft.getInstance();
        graphics.drawString(mc.font, mc.options.keyHotbarSlots[3].getTranslatedKeyMessage(), x + 16, y + 4,
                available ? 0xfff3d394 : 0xff948478);
    }

    private static double offset(StyleState state, double now) {
        if (state == null || state.phase().equals("OFF")) {
            return SLIDE_DISTANCE * (1 - ease((now - vanillaRise) / SLIDE_TICKS));
        }
        double phaseProgress = (now - state.phaseStart()) / SLIDE_TICKS;
        return switch (state.phase()) {
            case "DRAWING", "UPGRADING", StyleService.AWAKENING -> SLIDE_DISTANCE * ease(phaseProgress);
            case "SHEATHING" -> sheathOffset + (SLIDE_DISTANCE - sheathOffset) * ease(phaseProgress);
            case StyleService.SIMPLE_DRAWING -> now < attackRise
                    ? SLIDE_DISTANCE * ease(phaseProgress)
                    : SLIDE_DISTANCE * (1 - ease((now - attackRise) / SLIDE_TICKS));
            default -> SLIDE_DISTANCE * (1 - ease((now - attackRise) / SLIDE_TICKS));
        };
    }

    static boolean loweringVanillaHand(StyleState state, double now) {
        return state != null && (state.phase().equals(StyleService.SIMPLE_DRAWING) || state.phase().equals("DRAWING"))
                && now - state.phaseStart() < SLIDE_TICKS;
    }

    static double vanillaHandOffset(StyleState state, double now) {
        boolean vanilla = state == null || state.phase().equals("OFF") || loweringVanillaHand(state, now);
        return vanilla ? offset(state, now) / SLIDE_DISTANCE : 0;
    }

    static double ease(double progress) {
        double t = Math.clamp(progress, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
