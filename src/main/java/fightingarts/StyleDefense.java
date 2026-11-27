package fightingarts;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** The server owns both the guard input and the instant an incoming hit qualifies for a parry. */
public final class StyleDefense {
    public static final int ENERGY_COST = 35;
    public static final int RAISE_TICKS = 5;
    public static final int CHARGE_COOLDOWN_TICKS = 15 * 20;
    private static final int PARRY_TICKS = 2;
    private static final int PARRY_REARM_TICKS = 8;
    private static final int DISARM_TICKS = 20;
    private static final Map<ServerPlayer, Guard> GUARDS = new WeakHashMap<>();
    private static final Map<LivingEntity, Long> DISARMED = new WeakHashMap<>();

    private StyleDefense() {}

    public record Pose(UUID player, long raisedAt, long releasedAt) {
        public boolean held() { return releasedAt < 0; }

        public double raiseProgress(double tick) {
            double height = held() ? tick - raisedAt
                    : Math.min(RAISE_TICKS, releasedAt - raisedAt) - Math.max(0, tick - releasedAt);
            return Math.clamp(height / RAISE_TICKS, 0, 1);
        }
    }

    private record Guard(Pose pose, long parryUntil) {}

    public static boolean guarding(ServerPlayer player) {
        Guard guard = GUARDS.get(player);
        return guard != null && guard.pose.held();
    }

    public static boolean disarmed(LivingEntity entity) {
        if (entity.level().isClientSide) return false;
        Long until = DISARMED.get(entity);
        if (until == null) return false;
        if (entity.level().getGameTime() < until) return true;
        DISARMED.remove(entity);
        return false;
    }

    public static void setHeld(ServerPlayer player, boolean held) {
        Guard previous = GUARDS.get(player);
        boolean wasHeld = previous != null && previous.pose.held();
        if (held == wasHeld || held && (!StyleService.canRaiseGuard(player) || SantoryuSkills.visionActive(player))) return;
        long now = player.level().getGameTime();
        Pose pose;
        long parryUntil = 0;
        if (held) {
            long retained = previous == null ? 0 : Math.round(previous.pose.raiseProgress(now) * RAISE_TICKS);
            pose = new Pose(player.getUUID(), now - retained, -1);
            if (previous == null || now - previous.pose.releasedAt >= PARRY_REARM_TICKS) parryUntil = now + PARRY_TICKS;
            player.stopUsingItem();
            SantoryuSkills.stopNormalSweep(player);
        } else {
            pose = new Pose(player.getUUID(), previous.pose.raisedAt, now);
        }
        GUARDS.put(player, new Guard(pose, parryUntil));
        StyleNetwork.broadcastGuard(player, pose);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void block(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer defender) || !defender.isAlive() || event.getAmount() <= 0) return;
        Guard guard = GUARDS.get(defender);
        if (guard == null || !guard.pose.held() || !StyleService.canRaiseGuard(defender)
                || !fromFront(defender, event.getSource())
                || !SantoryuSkills.spendDefenseResource(defender, StyleService.current(defender).mode())) return;

        event.setCanceled(true);
        long now = defender.level().getGameTime();
        if (now < guard.parryUntil && event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker != defender && attacker.isAlive()) {
            DISARMED.put(attacker, now + DISARM_TICKS);
            GUARDS.put(defender, new Guard(guard.pose, 0));
            attacker.stopUsingItem();
            defender.level().playSound(null, defender, SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, .9f, 1.65f);
            var towardAttack = event.getSource().getSourcePosition().subtract(defender.position()).multiply(1, 0, 1).normalize();
            var contact = defender.getEyePosition().add(0, -.32, 0).add(towardAttack.scale(.60));
            SantoryuSkills.parryEffect(defender, contact, towardAttack);
        } else {
            defender.level().playSound(null, defender, SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, .7f, 1f);
        }
    }

    private static boolean fromFront(ServerPlayer player, DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_SHIELD)
                || source.getDirectEntity() instanceof AbstractArrow arrow && arrow.getPierceLevel() > 0) return false;
        var position = source.getSourcePosition();
        if (position == null) return false;
        var towardSource = position.subtract(player.position()).multiply(1, 0, 1);
        return towardSource.dot(player.getLookAngle()) > 0;
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var guard = GUARDS.get(player);
        if (guard == null) return;
        if (guard.pose.held() && !StyleService.canRaiseGuard(player)) setHeld(player, false);
        else if (!guard.pose.held() && player.level().getGameTime() - guard.pose.releasedAt >= PARRY_REARM_TICKS) GUARDS.remove(player);
    }

    @SubscribeEvent
    public static void track(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer watcher && event.getTarget() instanceof ServerPlayer player) {
            Guard guard = GUARDS.get(player);
            long ended = Math.max(0, player.level().getGameTime() - RAISE_TICKS);
            StyleNetwork.sendGuard(watcher, guard == null ? new Pose(player.getUUID(), ended, ended) : guard.pose);
        }
    }

    @SubscribeEvent
    public static void leave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof ServerPlayer player) GUARDS.remove(player);
        if (event.getEntity() instanceof LivingEntity entity) DISARMED.remove(entity);
    }
}
