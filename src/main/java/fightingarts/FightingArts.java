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
        StyleWeapons.register(modBus);
        SantoryuSkills.register(modBus);
        NeoForge.EVENT_BUS.register(SantoryuSkills.class);
        NeoForge.EVENT_BUS.register(StyleService.class);
        NeoForge.EVENT_BUS.register(StyleDefense.class);
        NeoForge.EVENT_BUS.register(CombatRestrictions.class);
        NeoForge.EVENT_BUS.register(CombatConsumables.class);
        NeoForge.EVENT_BUS.register(StyleWeapons.class);
        if (FMLEnvironment.dist == Dist.CLIENT) ClientHooks.install(modBus);
    }
}
