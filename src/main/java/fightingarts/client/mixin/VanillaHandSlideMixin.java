package fightingarts.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import fightingarts.client.FirstPersonStyleRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemInHandRenderer.class)
abstract class VanillaHandSlideMixin {
    @WrapMethod(method = "renderHandsWithItems")
    private void fightingarts$slide(float partialTick, PoseStack pose, MultiBufferSource.BufferSource buffers,
                                    LocalPlayer player, int light, Operation<Void> original) {
        double offset = FirstPersonStyleRenderer.vanillaHandOffset(partialTick);
        if (offset == 0) {
            original.call(partialTick, pose, buffers, player, light);
            return;
        }
        pose.pushPose();
        pose.translate(0, -1.25 * offset, 0);
        try {
            original.call(partialTick, pose, buffers, player, light);
        } finally {
            pose.popPose();
        }
    }
}
