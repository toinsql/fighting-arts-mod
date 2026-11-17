package fightingarts.client.mixin;

import fightingarts.client.StyleClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class CombatKeysMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void fightingarts$combatKeys(CallbackInfo ci) {
        StyleClient.handleCombatKeys();
    }
}
