package fightingarts.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import fightingarts.client.FirstPersonStyleRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemInHandRenderer.class)
abstract class VanillaHandSlideMixin {
    @ModifyExpressionValue(method = "renderHandsWithItems", at = @At(value = "FIELD", target =
            "Lnet/minecraft/client/renderer/ItemInHandRenderer$HandRenderSelection;renderMainHand:Z"))
    private boolean fightingarts$keepStyleHands(boolean vanillaMainHand, float partialTick) {
        return vanillaMainHand || FirstPersonStyleRenderer.usesStyleHands(partialTick);
    }

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
