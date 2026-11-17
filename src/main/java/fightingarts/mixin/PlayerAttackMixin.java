package fightingarts.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import fightingarts.StyleService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class PlayerAttackMixin {
    @ModifyExpressionValue(method = "attack", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/player/Player;getAttributeValue(Lnet/minecraft/core/Holder;)D", ordinal = 0))
    private double fightingarts$useStyleSwordDamage(double vanillaDamage, Entity target) {
        return (Object)this instanceof ServerPlayer player
                ? StyleService.styleAttackDamage(player, target, vanillaDamage) : vanillaDamage;
    }

    @WrapOperation(method = "attack", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean fightingarts$confirmFallingHit(Entity target, DamageSource source, float damage,
                                                   Operation<Boolean> hurt,
                                                   @Share("fallingDash") LocalBooleanRef fallingDash,
                                                   @Share("fallingHitDamage") LocalFloatRef fallingHitDamage) {
        boolean damaged = hurt.call(target, source, damage);
        if (damaged && (Object)this instanceof ServerPlayer player && StyleService.confirmHit(player, target)) {
            fallingDash.set(true);
            fallingHitDamage.set(damage);
        }
        return damaged;
    }

    @Inject(method = "attack", at = @At("RETURN"))
    private void fightingarts$extendDashAfterAttack(Entity target, CallbackInfo ci,
                                                   @Share("fallingDash") LocalBooleanRef fallingDash,
                                                   @Share("fallingHitDamage") LocalFloatRef fallingHitDamage) {
        if (fallingDash.get() && (Object)this instanceof ServerPlayer player) {
            StyleService.finishFallingDash(player, target, fallingHitDamage.get());
        }
    }
}
