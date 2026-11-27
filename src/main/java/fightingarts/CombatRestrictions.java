package fightingarts;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class CombatRestrictions {
    private CombatRestrictions() {}
    public static java.util.function.Predicate<Player> clientAttackMode=player->false;
    public static boolean active(Player player) {
        return player instanceof ServerPlayer serverPlayer
                ? StyleService.inAttackMode(serverPlayer) : player.level().isClientSide && clientAttackMode.test(player);
    }
    @SubscribeEvent public static void attack(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (SantoryuSkills.stunned(event.getEntity()) || event.getEntity() instanceof ServerPlayer player
                && (StyleDefense.disarmed(player) || StyleDefense.guarding(player) || SantoryuSkills.visionActive(player))) event.setCanceled(true);
    }
    @SubscribeEvent public static void damage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker
                && (SantoryuSkills.stunned(attacker) || StyleDefense.disarmed(attacker)
                        || attacker instanceof ServerPlayer player && SantoryuSkills.visionActive(player))) event.setCanceled(true);
    }
    @SubscribeEvent public static void leftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (active(event.getEntity()) || SantoryuSkills.stunned(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent public static void breakBlock(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer p && active(p) && !SantoryuSkills.cuttingBlock(p,event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public static void useBlock(PlayerInteractEvent.RightClickBlock event) {
        if (SantoryuSkills.stunned(event.getEntity()) || active(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent public static void useItem(PlayerInteractEvent.RightClickItem event) {
        if (SantoryuSkills.stunned(event.getEntity()) || active(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent public static void useEntity(PlayerInteractEvent.EntityInteract event) {
        if (SantoryuSkills.stunned(event.getEntity()) || active(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent public static void useEntitySpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (SantoryuSkills.stunned(event.getEntity()) || active(event.getEntity())) event.setCanceled(true);
    }
}
