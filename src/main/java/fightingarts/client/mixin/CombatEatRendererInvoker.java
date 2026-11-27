package fightingarts.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ItemInHandRenderer.class)
public interface CombatEatRendererInvoker {
    @Invoker("applyEatTransform")
    void fightingarts$eatTransform(PoseStack pose, float partialTick, HumanoidArm arm, ItemStack stack, Player player);

    @Invoker("applyItemArmTransform")
    void fightingarts$itemArmTransform(PoseStack pose, HumanoidArm arm, float equipProgress);
}
