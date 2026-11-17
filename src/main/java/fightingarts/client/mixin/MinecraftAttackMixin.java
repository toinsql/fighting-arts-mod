package fightingarts.client.mixin;

import fightingarts.StyleNetwork;
import fightingarts.client.StyleClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftAttackMixin {
    @org.spongepowered.asm.mixin.Shadow private int missTime;

    @Inject(method="startAttack",at=@At("HEAD"),cancellable=true)
    private void fightingarts$blockClick(CallbackInfoReturnable<Boolean> callback) {
        Minecraft mc=(Minecraft)(Object)this;
        if(!StyleClient.attackMode() || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK)return;
        StyleNetwork.requestAttack(false);
        callback.setReturnValue(false);
    }

    @Inject(method="continueAttack",at=@At("HEAD"),cancellable=true)
    private void fightingarts$holdToMine(boolean held,CallbackInfo callback) {
        if (!held) missTime = 0;
        Minecraft mc=(Minecraft)(Object)this;
        if (!StyleClient.attackMode()) return;
        mc.gameMode.stopDestroyBlock();
        callback.cancel();
    }
}
