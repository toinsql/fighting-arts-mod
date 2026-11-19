package fightingarts.mixin;

import fightingarts.SantoryuSkills;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class StunnedMovementMixin {
    @Inject(method="startUsingItem",at=@At("HEAD"),cancellable=true)
    private void fightingarts$preventCharge(net.minecraft.world.InteractionHand hand,CallbackInfo callback) {
        if (SantoryuSkills.stunned((LivingEntity)(Object)this)) callback.cancel();
    }
    @Inject(method="travel",at=@At("HEAD"),cancellable=true)
    private void fightingarts$stun(Vec3 input,CallbackInfo callback) {
        LivingEntity entity=(LivingEntity)(Object)this;
        if(SantoryuSkills.stunned(entity)){
            entity.setDeltaMovement(Vec3.ZERO);
            callback.cancel();
        }
    }
}
