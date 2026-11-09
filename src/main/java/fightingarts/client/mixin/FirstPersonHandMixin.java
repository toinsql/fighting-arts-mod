package fightingarts.client.mixin;

import fightingarts.client.FirstPersonStyleRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

@Mixin(targets = "net.neoforged.neoforge.client.ClientHooks")
abstract class FirstPersonHandMixin {
    @Inject(method = "renderSpecificFirstPersonHand(Lnet/minecraft/world/InteractionHand;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFLnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private static void fightingarts$renderBothHands(InteractionHand hand,PoseStack pose,MultiBufferSource buffers,int light,float partial,float pitch,float swing,float equip,ItemStack stack,CallbackInfoReturnable<Boolean> cir) {
        FirstPersonStyleRenderer.render(new net.neoforged.neoforge.client.event.RenderHandEvent(hand,pose,buffers,light,partial,pitch,swing,equip,stack),cir);
    }
}
