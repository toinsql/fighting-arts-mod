package fightingarts;

import net.neoforged.bus.api.IEventBus;

/** Client-only bridge; implemented by the rendering source set. */
public final class ClientHooks {
    private ClientHooks() {}
    public static void install(IEventBus modBus) { fightingarts.client.StyleClient.install(modBus); }
}
