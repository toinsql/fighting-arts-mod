package fightingarts.mixin;

import fightingarts.CombatRestrictions;
import fightingarts.SantoryuSkills;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class CombatWaterPlayerMixin {
    @Inject(method="updateSwimming",at=@At("HEAD"),cancellable=true)
    private void fightingarts$preventSwimmingInCombat(CallbackInfo callback){
        Player player=(Player)(Object)this;
        if(!CombatRestrictions.active(player))return;
        player.setSwimming(false);
        callback.cancel();
    }

    @Inject(method="isSwimming",at=@At("HEAD"),cancellable=true)
    private void fightingarts$neverSwimInCombat(CallbackInfoReturnable<Boolean> callback){
        if(CombatRestrictions.active((Player)(Object)this))callback.setReturnValue(false);
    }

    @Inject(method={"isAffectedByFluids","isPushedByFluid"},at=@At("HEAD"),cancellable=true)
    private void fightingarts$preserveLeapThroughFluids(CallbackInfoReturnable<Boolean> callback){
        if(SantoryuSkills.leapFlight((Player)(Object)this))callback.setReturnValue(false);
    }
}
