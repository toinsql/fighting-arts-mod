package fightingarts.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import fightingarts.client.ConsumableClient;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemInHandRenderer.class)
abstract class CombatEatRendererMixin {
    @Redirect(method = "applyEatTransform", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getUseItemRemainingTicks()I"))
    private int fightingarts$remainingUseTicks(Player player, PoseStack pose, float partialTick) {
        return ConsumableClient.remainingForAnimation(player, partialTick, player.getUseItemRemainingTicks());
    }

    @WrapOperation(method = "applyEatTransform", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;cos(F)F"))
    private float fightingarts$slowChew(float phase, Operation<Float> cos,
                                        @Local(argsOnly = true) float partialTick, @Local(argsOnly = true) Player player) {
        return cos.call(ConsumableClient.chewPhase(player, partialTick, phase));
    }
}
