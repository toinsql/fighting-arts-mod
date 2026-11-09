package fightingarts.client.mixin;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import fightingarts.client.AnimationRenderer;
@Mixin(value=PlayerModel.class, priority=900)
abstract class PlayerPoseMixin {
 @Inject(method="setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",at=@At("HEAD"))
 private void fightingarts$reset(LivingEntity entity,float swing,float amount,float age,float yaw,float pitch,CallbackInfo ci){PlayerModel<?> m=(PlayerModel<?>)(Object)this;for(var p:java.util.List.of(m.body,m.head,m.leftArm,m.rightArm,m.leftLeg,m.rightLeg))p.resetPose();}
 @Inject(method="setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",at=@At("TAIL"))
 private void fightingarts$pose(LivingEntity entity,float swing,float amount,float age,float yaw,float pitch,CallbackInfo ci) {
  if(entity instanceof AbstractClientPlayer player) AnimationRenderer.apply((PlayerModel<?>)(Object)this,player,age-player.tickCount);
 }
}
