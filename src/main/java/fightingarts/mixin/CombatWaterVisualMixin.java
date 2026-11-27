package fightingarts.mixin;

import fightingarts.CombatRestrictions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class CombatWaterVisualMixin {
    @Inject(method="getSwimAmount",at=@At("HEAD"),cancellable=true)
    private void fightingarts$keepCombatPoseUpright(float partialTick,CallbackInfoReturnable<Float> callback){
        if((Object)this instanceof Player player && CombatRestrictions.active(player))callback.setReturnValue(0f);
    }
}
