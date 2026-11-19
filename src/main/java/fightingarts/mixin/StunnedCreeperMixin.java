package fightingarts.mixin;

import fightingarts.SantoryuSkills;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Creeper.class)
abstract class StunnedCreeperMixin {
    @Shadow private int swell;
    @Shadow private int oldSwell;

    @Inject(method="tick",at=@At("HEAD"))
    private void fightingarts$resetFuse(CallbackInfo callback) {
        Creeper creeper=(Creeper)(Object)this;
        if (SantoryuSkills.stunned(creeper)) {
            swell=0;
            oldSwell=0;
            creeper.setSwellDir(-1);
        }
    }

    @Inject(method="isIgnited",at=@At("HEAD"),cancellable=true)
    private void fightingarts$pauseIgnition(CallbackInfoReturnable<Boolean> callback) {
        if (SantoryuSkills.stunned((Creeper)(Object)this)) callback.setReturnValue(false);
    }
}
