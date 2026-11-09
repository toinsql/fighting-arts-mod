package fightingarts.client.mixin;
import fightingarts.client.AnimationRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
@Mixin(ItemInHandLayer.class)
abstract class ItemLayerMixin {
 @Inject(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",at=@At("HEAD"),cancellable=true)
 private void fightingarts$items(PoseStack ps,MultiBufferSource b,int light,LivingEntity p,float a,float c,float d,float e,float f,float g,CallbackInfo ci){var s=AnimationRenderer.STATES.get(p.getUUID());if(s!=null&&!s.phase().equals("OFF"))ci.cancel();}
}
