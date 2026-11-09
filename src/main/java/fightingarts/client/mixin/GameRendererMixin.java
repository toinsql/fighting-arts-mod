package fightingarts.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import fightingarts.client.StyleClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = {"bobView", "bobHurt"}, at = @At("HEAD"), cancellable = true)
    private void fightingarts$lockCameraMotion(PoseStack pose, float partialTick, CallbackInfo ci) {
        if (StyleClient.isCinematic()) ci.cancel();
    }
}
