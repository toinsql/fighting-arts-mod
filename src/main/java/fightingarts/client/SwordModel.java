package fightingarts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fightingarts.SantoryuSkills;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Renders sword items with their registered vanilla or resource pack model. */
final class SwordModel {
    static final float ITEM_SCALE = .85f;
    static final float GRIP_COORDINATE = -.31f;
    static final float GUARD_COORDINATE = -.15f;
    static final float TIP_COORDINATE = .5f;
    private static final double GRIP_TO_CENTER = -Math.sqrt(2) * GRIP_COORDINATE * ITEM_SCALE;
    static final double GRIP_TO_TIP = GRIP_TO_CENTER + Math.sqrt(2) * TIP_COORDINATE * ITEM_SCALE;
    static final double GUARD_TO_TIP = Math.sqrt(2) * (TIP_COORDINATE - GUARD_COORDINATE) * ITEM_SCALE;
    private SwordModel() {}

    static float slimHandOffset(AbstractClientPlayer player, boolean left) {
        return player.getSkin().model() == PlayerSkin.Model.SLIM ? (left ? 0.5f : -0.5f) : 0;
    }

    static void render(PoseStack pose, MultiBufferSource buffers, int light,
                       AbstractClientPlayer player, ItemStack item, boolean blockbenchAxes) {
        if (item.isEmpty()) return;
        pose.pushPose();
        if (!blockbenchAxes) pose.mulPose(Axis.ZP.rotationDegrees(180));
        pose.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
        Minecraft.getInstance().getItemRenderer().renderStatic(player, item, ItemDisplayContext.NONE,
                false, pose, buffers, player.level(), light, OverlayTexture.NO_OVERLAY, player.getId());
        pose.popPose();
    }

    static double spinAge(AbstractClientPlayer player, float partial) {
        var state = SkillHud.STATUS.get(player.getUUID());
        if (state == null || state.skill() != 3) return -1;
        double age = (player.level().getGameTime() + (double) partial - state.start()) / 20;
        return age >= SantoryuSkills.SPIN_START && age < SantoryuSkills.SPIN_END
                ? age - SantoryuSkills.SPIN_START : -1;
    }

    /** Draws both blades and their wakes in one frame centered ahead of the player. */
    static void renderSpin(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                           List<ItemStack> swords, boolean firstPerson, double age) {
        float duration = (float) (SantoryuSkills.SPIN_END - SantoryuSkills.SPIN_START);
        float progress = Math.clamp((float) age / duration, 0, 1);
        float opacity = Math.min(1, Math.min(progress, 1 - progress) * 10);
        float angle = (float) (7 * Math.TAU * progress * progress * (3 - 2 * progress));
        pose.pushPose();
        // First person supplies the animated midpoint. Third person uses model space.
        if (!firstPerson) pose.translate(0, .44, -.67);
        pose.mulPose(Axis.ZP.rotation(firstPerson ? angle : -angle));
        for (int hand = 0; hand < 2; hand++) {
            pose.pushPose();
            // The items stay half a turn apart, with their handles near the same center.
            if (hand == 1) pose.mulPose(Axis.ZP.rotationDegrees(180));
            drawSpinWake(pose, buffers, hand == 1, firstPerson, age, opacity);
            // Offset the item origin by the baked handle's distance from center.
            pose.translate(GRIP_TO_CENTER, 0, 0);
            pose.mulPose(Axis.ZP.rotationDegrees(-45));
            render(pose, buffers, light, player, swords.get(hand), true);
            pose.popPose();
        }
        pose.popPose();
    }

    private static void drawSpinWake(PoseStack pose, MultiBufferSource buffers, boolean left,
                                     boolean firstPerson, double age, float opacity) {
        int direction = firstPerson ? 1 : -1;
        int arcCount = firstPerson ? 3 : 2;
        for (int arc = 0; arc < arcCount; arc++) {
            SkillMesh.Knot[] knots = new SkillMesh.Knot[21];
            double radius = GRIP_TO_TIP * (arc == 0 ? 1 : arc == 1 ? .67 : .43);
            double width = arc == 0 ? .14 : arc == 1 ? .085 : .045;
            if (firstPerson) width *= 1.2;
            float brightness = firstPerson ? (arc == 0 ? 1 : arc == 1 ? .8f : .65f)
                    : (arc == 0 ? .85f : .52f);
            for (int i = 0; i < knots.length; i++) {
                float t = i / (float) (knots.length - 1);
                double sweep = -direction * (1 - t) * (arc == 0 ? 2.65 : arc == 1 ? 1.95 : 1.45);
                Vec3 outward = new Vec3(Math.cos(sweep), Math.sin(sweep), 0);
                double taper = Math.sin(Math.PI * t);
                knots[i] = new SkillMesh.Knot(outward.scale(radius + .035 * Math.sin(t * 16)),
                        outward.scale(width * taper), t, opacity * brightness);
            }
            SkillMesh.ribbon(pose, buffers, SkillMesh.WIND, knots, left ? 0xd593ff : 0x80ffc3);
        }
        int sparks = firstPerson ? 7 : 4;
        for (int i = 0; i < sparks; i++) {
            double phase = (age * (4.3 + i * .19) + i * .27) % 1;
            double sweep = -direction * (i * .65 + phase);
            Vec3 outward = new Vec3(Math.cos(sweep), Math.sin(sweep), 0);
            double size = (.034 + (i % 4) * .01) * (1 - phase) * (firstPerson ? 1.45 : 1);
            double radius = firstPerson && i >= 4 ? GRIP_TO_TIP * .63 : GRIP_TO_TIP - .05;
            SkillMesh.sprite(pose, buffers, left ? SkillEffects.PURPLE_SPARK : SkillEffects.GREEN_SPARK,
                    outward.scale(radius + phase * .32), new Vec3(size, 0, 0), new Vec3(0, size, 0),
                    opacity * (float) (1 - phase), 0xffffff);
        }
    }
}
