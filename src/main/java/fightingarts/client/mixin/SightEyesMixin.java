package fightingarts.client.mixin;

import fightingarts.client.SkillHud;
import net.minecraft.client.player.AbstractClientPlayer;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="punchymoves.client.PlayerAnimationEngine",remap=false)
abstract class SightEyesMixin {
    @Inject(method="emoteFace",at=@At("HEAD"),cancellable=true,require=0)
    private static void closedEyes(AbstractClientPlayer player,CallbackInfoReturnable<String> result){
        if(player==null || !ModList.get().isLoaded("punchy"))return;
        var status=SkillHud.STATUS.get(player.getUUID());
        if(status!=null && status.visionStart()>=0)result.setReturnValue("shut");
    }
}
