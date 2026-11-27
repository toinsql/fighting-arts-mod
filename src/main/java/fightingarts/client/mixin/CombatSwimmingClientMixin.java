package fightingarts.client.mixin;

import fightingarts.client.StyleClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
abstract class CombatSwimmingClientMixin {
    @ModifyVariable(method = "setSwimming", at = @At("HEAD"), argsOnly = true)
    private boolean fightingarts$disablePredictedSwimming(boolean swimming) {
        return swimming && ((Object) this != Minecraft.getInstance().player || !StyleClient.attackMode());
    }
}
