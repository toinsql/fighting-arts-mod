package fightingarts.client.mixin;

import fightingarts.client.FirstPersonStyleRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Punchy is optional; suppress only its separate first-person render pass. */
@Pseudo
@Mixin(targets = "punchy.client.render.PunchyArmRenderer", remap = false)
abstract class PunchyArmRendererMixin {
    @Inject(method = "renderFirstPerson", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void fightingarts$hidePunchyHands(CallbackInfo ci) {
        float partialTick = net.minecraft.client.Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        if (FirstPersonStyleRenderer.ownsHands() && !FirstPersonStyleRenderer.loweringVanillaHand(partialTick)) ci.cancel();
    }
}
