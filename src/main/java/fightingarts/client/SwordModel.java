package fightingarts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Renders sword items with their registered vanilla or resource pack model. */
final class SwordModel {
    private SwordModel() {}

    static void render(PoseStack pose, MultiBufferSource buffers, int light,
                       AbstractClientPlayer player, ItemStack item, boolean blockbenchAxes) {
        if (item.isEmpty()) return;
        pose.pushPose();
        if (!blockbenchAxes) pose.mulPose(new org.joml.Quaternionf().rotationZ((float) Math.PI));
        pose.scale(.85f, .85f, .85f);
        Minecraft.getInstance().getItemRenderer().renderStatic(player, item, ItemDisplayContext.NONE,
                false, pose, buffers, player.level(), light, OverlayTexture.NO_OVERLAY, player.getId());
        pose.popPose();
    }
}
