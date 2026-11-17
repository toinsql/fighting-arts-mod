package fightingarts.client.mixin;

import fightingarts.client.StyleClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.util.SmoothDouble;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Shadow @Final private SmoothDouble smoothTurnX;
    @Shadow @Final private SmoothDouble smoothTurnY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void fightingarts$lockTurn(double frameTime, CallbackInfo ci) {
        if (StyleClient.isCinematic() || fightingarts.client.SkillHud.aimLocked()) {
            smoothTurnX.reset();
            smoothTurnY.reset();
            ci.cancel();
        }
    }
}
