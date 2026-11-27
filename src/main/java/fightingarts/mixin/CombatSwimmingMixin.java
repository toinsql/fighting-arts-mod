package fightingarts.mixin;

import fightingarts.CombatRestrictions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
abstract class CombatSwimmingMixin {
    @ModifyVariable(method = "setSwimming", at = @At("HEAD"), argsOnly = true)
    private boolean fightingarts$disableSwimmingInCombat(boolean swimming) {
        return swimming && !((Entity) (Object) this instanceof Player player && CombatRestrictions.active(player));
    }
}
