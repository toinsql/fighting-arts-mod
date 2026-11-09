package fightingarts;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

@Mod(FightingArts.MOD_ID)
public final class FightingArts {
    public static final String MOD_ID = "fightingarts";

    public FightingArts(IEventBus modBus) {
        StyleNetwork.registerPayloads(modBus);
        NeoForge.EVENT_BUS.register(StyleService.class);
        if (FMLEnvironment.dist == Dist.CLIENT) ClientHooks.install(modBus);
    }
}
