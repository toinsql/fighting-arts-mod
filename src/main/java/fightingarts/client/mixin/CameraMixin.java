package fightingarts.client.mixin;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import fightingarts.client.StyleClient;
import fightingarts.client.AnimationRenderer;
import net.minecraft.client.Minecraft;
@Mixin(Camera.class)
abstract class CameraMixin {
 @Shadow protected abstract void setPosition(double x,double y,double z);
 @Shadow protected abstract void setRotation(float yaw,float pitch);
 @Inject(method="setup",at=@At("TAIL")) private void fightingarts$camera(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
  double[] pose=StyleClient.camera(partialTick); if(pose!=null){setPosition(pose[0],pose[1],pose[2]);setRotation((float)pose[3],(float)pose[4]);}
  else if(entity == Minecraft.getInstance().player && Minecraft.getInstance().player != null) {
   double offset = AnimationRenderer.landingCameraOffset(Minecraft.getInstance().player, partialTick);
   var position = ((Camera)(Object)this).getPosition();
   setPosition(position.x, position.y + offset, position.z);
  }
 }
}
