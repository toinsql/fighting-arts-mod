package fightingarts;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.common.util.TriState;

public final class CombatRestrictions {
    private CombatRestrictions() {}
    public static boolean active(Player player) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return false;
        var state=StyleService.current(serverPlayer);
        return state!=null && !state.phase().equals("OFF");
    }
    @SubscribeEvent public static void leftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (active(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent public static void breakBlock(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (active(event.getPlayer())) event.setCanceled(true);
    }
    @SubscribeEvent public static void useBlock(PlayerInteractEvent.RightClickBlock event) {
        if(active(event.getEntity()))event.setUseItem(TriState.FALSE);
    }
    @SubscribeEvent public static void useItem(PlayerInteractEvent.RightClickItem event) {
        if(active(event.getEntity()) && event.getItemStack().getItem() instanceof BlockItem)event.setCanceled(true);
    }
}
