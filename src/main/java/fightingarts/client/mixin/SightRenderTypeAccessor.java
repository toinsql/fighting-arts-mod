package fightingarts.client.mixin;

import java.util.Optional;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets="net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface SightRenderTypeAccessor {
    @Accessor("state")
    RenderType.CompositeState fightingarts$state();

    @Mixin(RenderType.CompositeState.class)
    interface State {
        @Accessor("textureState")
        RenderStateShard.EmptyTextureStateShard fightingarts$textureState();
    }

    @Mixin(RenderStateShard.EmptyTextureStateShard.class)
    interface Texture {
        @Invoker("cutoutTexture")
        Optional<ResourceLocation> fightingarts$cutoutTexture();
    }
}
