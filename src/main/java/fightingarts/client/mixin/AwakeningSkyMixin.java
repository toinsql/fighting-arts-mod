package fightingarts.client.mixin;

import fightingarts.client.AwakeningEffects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
abstract class AwakeningSkyMixin {
    @Inject(method="getSkyColor",at=@At("RETURN"),cancellable=true)
    private void fightingarts$sky(Vec3 position,float partial,CallbackInfoReturnable<Vec3> result) {
        result.setReturnValue(AwakeningEffects.sky(result.getReturnValue(),partial));
    }
    @Inject(method="getCloudColor",at=@At("RETURN"),cancellable=true)
    private void fightingarts$clouds(float partial,CallbackInfoReturnable<Vec3> result) {
        result.setReturnValue(AwakeningEffects.sky(result.getReturnValue(),partial));
    }
}
