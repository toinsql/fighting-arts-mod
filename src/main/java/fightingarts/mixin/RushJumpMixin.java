package fightingarts.mixin;

import fightingarts.SantoryuSkills;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class RushJumpMixin {
    @Inject(method="jumpFromGround",at=@At("HEAD"),cancellable=true)
    private void fightingarts$keepRushHorizontal(CallbackInfo callback){
        if(SantoryuSkills.rushing((Player)(Object)this))callback.cancel();
    }
}
