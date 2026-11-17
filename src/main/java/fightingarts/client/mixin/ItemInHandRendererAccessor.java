package fightingarts.client.mixin;

import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccessor {
    @Accessor("oMainHandHeight")
    void fightingarts$setPreviousMainHandHeight(float height);

    @Accessor("oOffHandHeight")
    void fightingarts$setPreviousOffHandHeight(float height);
}
