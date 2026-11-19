package fightingarts.mixin;

import fightingarts.SantoryuSkills;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
abstract class StunnedMobMixin {
    @Shadow @org.spongepowered.asm.mixin.Final protected GoalSelector goalSelector;

    @Inject(method="serverAiStep",at=@At("HEAD"),cancellable=true)
    private void fightingarts$pauseGoals(CallbackInfo callback) {
        Mob mob=(Mob)(Object)this;
        if (!SantoryuSkills.stunned(mob)) return;
        goalSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning).forEach(WrappedGoal::stop);
        mob.getNavigation().stop();
        callback.cancel();
    }

    @Inject(method="doHurtTarget",at=@At("HEAD"),cancellable=true)
    private void fightingarts$preventAttack(net.minecraft.world.entity.Entity target,CallbackInfoReturnable<Boolean> callback) {
        if (SantoryuSkills.stunned((Mob)(Object)this)) callback.setReturnValue(false);
    }
}
