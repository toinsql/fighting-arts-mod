package fightingarts;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.*;

/** Authoritative Three Sword Style state and combat timing. */
public final class StyleService {
    public static final int DRAW_TICKS = 65;
    public static final int ENTRY_TICKS = DRAW_TICKS + 30;
    public static double upgradeAnimationSeconds(double elapsedTicks) {
        return elapsedTicks * 53 / (20.0 * DRAW_TICKS);
    }
    public static final int SIMPLE_ENTRY_TICKS = 20;
    public static final String SIMPLE_DRAWING = "SIMPLE_DRAWING";
    public static final int SHEATH_TICKS = 50;
    // exit_mode_simple lasts 1.41667 seconds at 20 ticks per second.
    public static final int SIMPLE_SHEATH_TICKS = 29;
    public static final int COMBO1_WINDOW_TICKS = 40;
    public static final int COMBO_WINDOW_TICKS = 30;
    public static final int COMBO12_LOCK_TICKS = 10;
    public static final int COMBO3_LOCK_TICKS = 20;
    public static final int SHEATH_SLOW_TICKS = 50;
    private static final int FALLING_LANDING_GRACE_TICKS = 2;
    private static final double FALLING_MIN_DROP = 3.0;
    private static final double FALLING_HEIGHT_EPSILON = 1e-6;
    private static final float FALLING_LOOK_DOWN_PITCH = 5;
    private static final double FALLING_DASH_SPEED = 0.32;
    private static final double FALLING_DASH_EXTRA_DISTANCE = 3.0;
    private static final double FALLING_DASH_HIT_MARGIN = 0.35;
    private static final float FALLING_DASH_DAMAGE_RATIO = 0.25f;
    private static final double FALLING_DASH_MIN_TRAIL_SPEED = 0.10;
    private static final double FALLING_DASH_DRAG = 0.91;
    private static final Map<UUID, MutableState> STATES = new HashMap<>();
    private static final Map<UUID, List<ItemStack>> OFF_SWORDS = new HashMap<>();
    public static final ResourceLocation SHEATH_SLOW = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID, "sheathing_slow");
    private StyleService() {}

    public static StyleState current(ServerPlayer player) {
        MutableState state = STATES.get(player.getUUID());
        return state == null ? null : snapshot(player.getUUID(), state);
    }

    public static void toggle(ServerPlayer player, StyleMode mode) {
        if (mode == StyleMode.NONE || SantoryuSkills.busy(player)) return;
        boolean simple = mode == StyleMode.SIMPLE;
        MutableState state = STATES.get(player.getUUID());
        long now = player.serverLevel().getGameTime();
        if (state == null || state.phase.equals("OFF")) {
            if (!player.isAlive() || player.isSpectator() || !player.getOffhandItem().isEmpty()
                    || (simple && !player.getMainHandItem().isEmpty())) return;
            List<ItemStack> swords = findSwords(player);
            if (swords.size() < StyleWeapons.SANTORYU_SLOTS) return;
            state = new MutableState(mode, simple ? SIMPLE_DRAWING : "DRAWING", now, swords, player.position(),
                    !simple && !player.onGround() && !player.getAbilities().flying
                            && player.level().noCollision(player, player.getBoundingBox().move(0, -.05, 0)));
            STATES.put(player.getUUID(), state);
            player.stopUsingItem();
        } else if (state.mode == StyleMode.SIMPLE && mode == StyleMode.COMMON && state.phase.equals("ACTIVE")
                && player.getOffhandItem().isEmpty() && !SantoryuSkills.busy(player)) {
            state = new MutableState(mode, "UPGRADING", now, findSwords(player), player.position(), false);
            STATES.put(player.getUUID(), state);
        } else if (state.mode == mode && (state.phase.equals("ACTIVE") || state.phase.equals(SIMPLE_DRAWING))) {
            state.phase = "SHEATHING";
            state.fallingAttackStart = -1;
            state.fallingHitStart = -1;
            state.fallingHitTarget = null;
            state.dashSweep = null;
            state.phaseStart = now;
            var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.addTransientModifier(new AttributeModifier(SHEATH_SLOW, -0.30, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else return;
        sync(player, state);
    }

    public static void attack(ServerPlayer player, boolean fallingOnly) {
        attack(player, null, fallingOnly);
    }

    private static void attack(ServerPlayer player, Entity target, boolean fallingOnly) {
        MutableState s = STATES.get(player.getUUID());
        long now = player.serverLevel().getGameTime();
        boolean leapHit = SantoryuSkills.leapStrike(player, target);
        if (s == null || !s.phase.equals("ACTIVE") || locked(s, now)
                || (SantoryuSkills.busy(player) && !leapHit)) return;
        if (leapHit) {
            SantoryuSkills.interruptLeap(player);
            s.leapHitTarget = target;
            s.leapHitTick = now;
        }
        if (!leapHit && canUseFallingAttack(player, s, now)) {
            s.fallingAttackStart = -1;
            s.fallingLandingExpiry = -1;
            s.fallingAttackUsed = true;
            s.fallingHitStart = now;
            s.fallingHitTarget = target;
            sync(player, s);
            return;
        }

        if (fallingOnly) return;
        s.fallingHitStart = -1;
        s.fallingHitTarget = null;

        int comboWindow = s.combo == 1 ? COMBO1_WINDOW_TICKS : COMBO_WINDOW_TICKS;
        s.combo = (s.combo == 1 || s.combo == 2) && now - s.comboStart < comboWindow ? s.combo % 3 + 1 : 1;
        s.comboStart = now;
        SantoryuSkills.normalSweep(player, s.combo, target);
        sync(player, s);
    }

    private static boolean canUseFallingAttack(ServerPlayer player, MutableState state, long now) {
        if (SantoryuSkills.suppressFalling(player) || state.fallingAttackStart < 0) return false;
        if (player.onGround()) {
            return state.fallingLandingExpiry >= 0 ? now < state.fallingLandingExpiry
                    : state.fallTopY - player.getY() >= FALLING_MIN_DROP - FALLING_HEIGHT_EPSILON;
        }
        return state.fallingLandingExpiry < 0 && hasFallingAttackHeight(player, state.fallTopY);
    }

    public static double styleAttackDamage(ServerPlayer player, Entity target, double vanillaDamage) {
        MutableState state = STATES.get(player.getUUID());
        if (state == null || !state.phase.equals("ACTIVE")) return vanillaDamage;
        long now = player.serverLevel().getGameTime();
        List<ItemStack> weapons;
        if (isFallingStrike(state, target, now) || state.leapHitTarget == target && target != null && state.leapHitTick == now) {
            weapons = state.swords.subList(0, state.mode == StyleMode.SIMPLE ? 2 : 3);
        } else if (state.combo != 0 && state.comboStart == now) {
            weapons = switch (state.combo) {
                case 1 -> state.swords.subList(0, 1);
                case 2 -> state.swords.subList(1, 2);
                default -> state.swords.subList(0, 2);
            };
        } else return vanillaDamage;

        AttributeInstance original = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (original == null) return vanillaDamage;
        AttributeInstance damage = new AttributeInstance(Attributes.ATTACK_DAMAGE, ignored -> {});
        damage.replaceFrom(original);
        player.getMainHandItem().forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)) damage.removeModifier(modifier.id());
        });

        for (int i = 0; i < weapons.size(); i++) addSwordDamage(damage, weapons.get(i), i);
        // Each sword's displayed damage includes the vanilla player's one damage point.
        damage.setBaseValue(damage.getBaseValue() + weapons.size() - 1);
        return damage.getValue();
    }

    private static void addSwordDamage(AttributeInstance damage, ItemStack sword, int weaponIndex) {
        sword.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (!attribute.equals(Attributes.ATTACK_DAMAGE)) return;
            ResourceLocation id = modifier.id();
            if (weaponIndex > 0) {
                id = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,
                        "style_weapon/" + weaponIndex + "/" + id.getNamespace() + "/" + id.getPath());
                modifier = new AttributeModifier(id, modifier.amount(), modifier.operation());
            }
            damage.removeModifier(id);
            damage.addTransientModifier(modifier);
        });
    }

    /** Called when the primary target accepts damage from the vanilla attack. */
    public static boolean confirmHit(ServerPlayer player, Entity target) {
        MutableState state = STATES.get(player.getUUID());
        long now = player.serverLevel().getGameTime();
        if (state != null && state.leapHitTarget == target && state.leapHitTick == now) {
            SantoryuSkills.stopNormalSweep(player);
            state.leapHitTarget = null;
            state.fallingAttackStart = -1;
            state.fallingLandingExpiry = -1;
            state.fallingAttackUsed = true;
            state.fallingHitStart = now;
            state.fallingHitTarget = target;
            state.combo = 0;
            sync(player, state);
        }
        if (!isFallingStrike(state, target, now)) return false;

        state.fallingHitTarget = null;
        return true;
    }

    private static boolean isFallingStrike(MutableState state, Entity target, long now) {
        return state != null && state.fallingHitStart == now && state.fallingHitTarget == target;
    }

    public static void finishFallingDash(ServerPlayer player, Entity primaryTarget, float hitDamage) {
        MutableState state = STATES.get(player.getUUID());
        if (state == null || !state.phase.equals("ACTIVE")) return;

        Vec3 forward = Vec3.directionFromRotation(0, player.getYRot());
        Vec3 extra = forward.scale(FALLING_DASH_EXTRA_DISTANCE);
        var box = player.getBoundingBox();
        var level = player.serverLevel();
        Vec3 collisionLimited = Entity.collideBoundingBox(player, extra, box, level,
                level.getEntityCollisions(player, box.expandTowards(extra)));
        // Keep the dash on its heading when one axis runs into an obstacle.
        double fractionX = Math.abs(extra.x) > 1e-6 ? collisionLimited.x / extra.x : 1;
        double fractionZ = Math.abs(extra.z) > 1e-6 ? collisionLimited.z / extra.z : 1;
        Vec3 allowed = extra.scale(Math.clamp(Math.min(fractionX, fractionZ), 0, 1));
        if (allowed.horizontalDistanceSqr() < 1e-8 || !level.noCollision(player, box.move(allowed))) return;

        player.connection.teleport(player.getX() + allowed.x, player.getY(), player.getZ() + allowed.z,
                player.getYRot(), player.getXRot(), Set.of(RelativeMovement.Y, RelativeMovement.Y_ROT, RelativeMovement.X_ROT));

        DashSweep dash = new DashSweep(level, forward, player.position(), hitDamage * FALLING_DASH_DAMAGE_RATIO,
                FALLING_DASH_SPEED);
        // The primary target took the full sword hit and is excluded from every dash segment.
        dash.hit.add(primaryTarget);
        state.dashSweep = dash;
        applyDashImpulse(player, dash);
        damageDashPath(player, state, dash, box, allowed);
    }

    private static void applyDashImpulse(ServerPlayer player, DashSweep dash) {
        Vec3 impulse = dash.forward.scale(dash.speed);
        player.setDeltaMovement(impulse.x, player.getDeltaMovement().y, impulse.z);
        StyleNetwork.sendDashImpulse(player, impulse.x, impulse.z);
    }

    private static void updateDashSweep(ServerPlayer player, MutableState state) {
        DashSweep dash = state.dashSweep;
        if (dash == null) return;
        if (dash.level != player.serverLevel() || dash.speed < FALLING_DASH_MIN_TRAIL_SPEED) {
            state.dashSweep = null;
            return;
        }

        Vec3 movement = player.position().subtract(dash.lastPosition);
        double advance = movement.dot(dash.forward);
        if (movement.horizontalDistanceSqr() < 0.0001 && dash.stationaryTicks++ < 2) {
            // Allow a short gap for movement packets or contact with the ground.
            dash.lastPosition = player.position();
            applyDashImpulse(player, dash);
            return;
        }
        if (advance <= 0.01 || movement.horizontalDistanceSqr() > 4 || Math.abs(movement.y) > 8
                || advance * advance < movement.horizontalDistanceSqr() * 0.5) {
            state.dashSweep = null;
            return;
        }

        AABB start = player.getBoundingBox().move(movement.scale(-1));
        // Cover only the forward impulse when the player also adds their own movement.
        Vec3 traveled = movement.scale(Math.min(1, dash.speed / advance));
        damageDashPath(player, state, dash, start, traveled);
        dash.lastPosition = player.position();
        dash.stationaryTicks = 0;

        // Give the impulse its own decay so ground friction cannot cut it short.
        dash.speed *= FALLING_DASH_DRAG;
        if (dash.speed >= FALLING_DASH_MIN_TRAIL_SPEED) applyDashImpulse(player, dash);
    }

    private static void damageDashPath(ServerPlayer player, MutableState state, DashSweep dash, AABB box, Vec3 movement) {
        if (dash.damage <= 0) return;
        var sweepBox = box.inflate(FALLING_DASH_HIT_MARGIN, 0.25, FALLING_DASH_HIT_MARGIN);
        Vec3 start = sweepBox.getCenter();
        Vec3 end = start.add(movement);

        var source = player.damageSources().playerAttack(player);
        for (Mob mob : dash.level.getEntitiesOfClass(Mob.class, sweepBox.expandTowards(movement),
                target -> !dash.hit.contains(target) && target.isAlive() && target.isAttackable())) {
            // Expand each target by the dash hitbox so the ray tests the swept volume, even diagonally.
            var targetBox = mob.getBoundingBox().inflate(
                    sweepBox.getXsize() / 2, sweepBox.getYsize() / 2, sweepBox.getZsize() / 2);
            if (!targetBox.contains(start) && !targetBox.contains(end) && targetBox.clip(start, end).isEmpty()) continue;

            Vec3 center = mob.getBoundingBox().getCenter();
            double alongDash = Math.clamp(center.subtract(start).dot(movement) / movement.lengthSqr(), 0, 1);
            Vec3 nearest = start.add(movement.scale(alongDash));
            if (dash.level.clip(new ClipContext(nearest, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                    .getType() == HitResult.Type.MISS) {
                dash.hit.add(mob);
                state.dashDamageTarget = mob;
                try {
                    mob.hurt(source, dash.damage);
                } finally {
                    state.dashDamageTarget = null;
                }
            }
        }
    }

    private static void updateFallingAttack(ServerPlayer player, MutableState state, long now) {
        long previousStart = state.fallingAttackStart;
        // Server players receive position updates, so sample descent from their tick-to-tick displacement.
        double verticalMovement = player.getY() - state.lastY;
        state.lastY = player.getY();
        state.fallingHitTarget = null;
        if (SantoryuSkills.suppressFalling(player) || player.getAbilities().flying || player.isFallFlying() || player.isInWater()
                || player.isInLava() || player.isPassenger() || player.onClimbable()) {
            state.fallTopY = player.getY();
            state.fallingAttackStart = -1;
            state.fallingLandingExpiry = -1;
            state.fallingAttackUsed = false;
        } else if (player.onGround()) {
            state.fallingAttackUsed = false;
            if (state.fallingAttackStart >= 0 && state.fallingLandingExpiry < 0) {
                if (state.fallTopY - player.getY() >= FALLING_MIN_DROP - FALLING_HEIGHT_EPSILON) {
                    state.fallingLandingExpiry = now + FALLING_LANDING_GRACE_TICKS;
                } else {
                    state.fallingAttackStart = -1;
                }
            }
            state.fallTopY = player.getY();
            if (state.fallingLandingExpiry >= 0 && now >= state.fallingLandingExpiry) {
                state.fallingAttackStart = -1;
                state.fallingLandingExpiry = -1;
            }
        } else {
            boolean leftGroundAgain = state.fallingLandingExpiry >= 0;
            state.fallingLandingExpiry = -1;
            state.fallTopY = Math.max(state.fallTopY, player.getY());
            boolean enoughDrop = !state.fallingAttackUsed && hasFallingAttackHeight(player, state.fallTopY);
            if (leftGroundAgain || verticalMovement > 0 || !enoughDrop) {
                state.fallingAttackStart = -1;
            }
            if (enoughDrop && state.fallingAttackStart < 0
                    && verticalMovement < 0 && player.getXRot() >= FALLING_LOOK_DOWN_PITCH) {
                state.fallingAttackStart = now;
                state.fallingHitStart = -1;
                state.combo = 0;
            }
        }
        if (state.fallingAttackStart != previousStart) sync(player, state);
    }

    private static boolean hasFallingAttackHeight(ServerPlayer player, double fallTopY) {
        // Measure the remaining drop under the player's footprint, including slab and stair collision shapes.
        double required = FALLING_MIN_DROP - (fallTopY - player.getY());
        if (required <= FALLING_HEIGHT_EPSILON) return true;
        Vec3 downward = new Vec3(0, -required, 0);
        Vec3 allowed = Entity.collideBoundingBox(player, downward, player.getBoundingBox(), player.level(), List.of());
        return allowed.y <= -required + FALLING_HEIGHT_EPSILON;
    }

    private static boolean locked(MutableState s, long now) {
        return isAttackLocked(s.phase, s.combo, now - s.comboStart);
    }

    public static boolean isAttackLocked(String phase, int combo, long ticksSinceCombo) {
        if (phase.equals("UPGRADING") || phase.equals("DRAWING") || phase.equals(SIMPLE_DRAWING) || phase.equals("SHEATHING")) return true;
        if (!phase.equals("ACTIVE")) return false;
        return switch (combo) {
            case 1, 2 -> ticksSinceCombo < COMBO12_LOCK_TICKS;
            case 3 -> ticksSinceCombo < COMBO3_LOCK_TICKS;
            default -> false;
        };
    }

    public static List<ItemStack> findSwords(Player p) {
        return StyleWeapons.of(p).swords();
    }

    private static void sync(ServerPlayer p, MutableState s) { StyleNetwork.broadcast(p, snapshot(p.getUUID(), s)); }
    private static StyleState snapshot(UUID id, MutableState s) {
        boolean entryAirborne = s.phase.equals("DRAWING") && s.airborneEntry && !s.entryLanded;
        return new StyleState(id, s.phase, s.phaseStart, s.combo, s.comboStart, s.swords, s.fallingAttackStart,
                s.fallingHitStart, entryAirborne, s.mode);
    }

    @SubscribeEvent public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        MutableState s = STATES.get(p.getUUID());
        if (s == null) {
            List<ItemStack> nowSwords = findSwords(p);
            List<ItemStack> previous = OFF_SWORDS.put(p.getUUID(), nowSwords);
            if (!sameLists(previous, nowSwords))
                StyleNetwork.broadcast(p, new StyleState(p.getUUID(), "OFF", p.serverLevel().getGameTime(), 0, 0, nowSwords));
            return;
        }
        long now = p.serverLevel().getGameTime();
        List<ItemStack> currentSwords = findSwords(p);
        int sheathTicks = s.mode == StyleMode.SIMPLE ? SIMPLE_SHEATH_TICKS : SHEATH_TICKS;
        if (currentSwords.size() < StyleWeapons.SANTORYU_SLOTS || !p.getOffhandItem().isEmpty()
                || p.isSpectator() || (s.phase.equals("SHEATHING") && now - s.phaseStart >= sheathTicks)) {
            STATES.remove(p.getUUID());
            removeSlow(p);
            OFF_SWORDS.put(p.getUUID(), currentSwords);
            StyleNetwork.broadcast(p, new StyleState(p.getUUID(), "OFF", now, 0, 0, currentSwords));
            return;
        }
        if (s.phase.equals("ACTIVE")) {
            if (!sameLists(s.swords, currentSwords)) {
                s.swords = currentSwords;
                sync(p, s);
            }
            updateFallingAttack(p, s, now);
            updateDashSweep(p, s);
        }
        long age = now - s.phaseStart;
        if (((s.phase.equals("DRAWING") || s.phase.equals("UPGRADING")) && age >= ENTRY_TICKS)
                || (s.phase.equals(SIMPLE_DRAWING) && age >= SIMPLE_ENTRY_TICKS)) {
            s.swords = currentSwords;
            s.phase = "ACTIVE";
            s.phaseStart = now;
            s.lastY = s.fallTopY = p.getY();
            sync(p,s);
        }
        if (s.phase.equals("DRAWING") || s.phase.equals("UPGRADING")) {
            if (s.airborneEntry) {
                if (!s.entryLanded && p.onGround()) {
                    s.entryLanded = true;
                    sync(p, s);
                }
                Vec3 movement = p.getDeltaMovement();
                p.setDeltaMovement(0, movement.y, 0);
            } else {
                p.teleportTo(s.anchor.x, s.anchor.y, s.anchor.z); p.setDeltaMovement(Vec3.ZERO); p.hurtMarked = true;
            }
        }
    }

    @SubscribeEvent
    public static void keepOffhandEmpty(net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent.Hands event) {
        if (event.getEntity() instanceof ServerPlayer player && STATES.containsKey(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void attackEvent(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        MutableState s=STATES.get(p.getUUID());
        if (s == null) return;
        if ((SantoryuSkills.busy(p) && !SantoryuSkills.leapStrike(p,e.getTarget())) || locked(s,p.serverLevel().getGameTime())) { e.setCanceled(true); return; }
        if (s.phase.equals("ACTIVE")) attack(p, e.getTarget(), false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void replaceFallingSweep(SweepAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && isFallingStrike(STATES.get(player.getUUID()), event.getTarget(), player.serverLevel().getGameTime())) {
            event.setSweeping(false);
        }
    }

    @SubscribeEvent
    public static void showStyleDamage(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0 || !event.getSource().is(DamageTypes.PLAYER_ATTACK)
                || !(event.getSource().getDirectEntity() instanceof ServerPlayer attacker)) return;

        MutableState state = STATES.get(attacker.getUUID());
        if (state == null || !state.phase.equals("ACTIVE")) return;
        long now = attacker.serverLevel().getGameTime();
        if (state.dashDamageTarget != event.getEntity() && state.fallingHitStart != now
                && (state.combo == 0 || state.comboStart != now)) return;

        var target = event.getEntity();
        Vec3 position = new Vec3(target.getX(), target.getY(0.85) + 0.18, target.getZ());
        StyleNetwork.sendDamageNumber(attacker, position, event.getNewDamage());
    }

    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) syncExisting(p);
    }
    @SubscribeEvent public static void tracking(PlayerEvent.StartTracking e) {
        if (e.getEntity() instanceof ServerPlayer watcher && e.getTarget() instanceof ServerPlayer tracked) {
            MutableState s=STATES.get(tracked.getUUID());
            StyleNetwork.send(watcher,s!=null?snapshot(tracked.getUUID(),s):new StyleState(tracked.getUUID(),"OFF",tracked.serverLevel().getGameTime(),0,0,findSwords(tracked)));
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if(e.getEntity() instanceof ServerPlayer p) { STATES.remove(p.getUUID()); OFF_SWORDS.remove(p.getUUID()); removeSlow(p); StyleNetwork.broadcast(p,new StyleState(p.getUUID(),"OFF",p.serverLevel().getGameTime(),0,0,findSwords(p))); }
    }
    @SubscribeEvent public static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            STATES.remove(p.getUUID());
            OFF_SWORDS.remove(p.getUUID());
            removeSlow(p);
            StyleNetwork.broadcast(p, new StyleState(p.getUUID(), "OFF", p.serverLevel().getGameTime(), 0, 0, List.of()));
        }
    }
    private static boolean sameLists(List<ItemStack> a,List<ItemStack> b){ if(a==null||a.size()!=b.size())return false; for(int i=0;i<a.size();i++) if(!ItemStack.matches(a.get(i),b.get(i))) return false; return true; }
    private static void removeSlow(ServerPlayer p){ var speed=p.getAttribute(Attributes.MOVEMENT_SPEED); if(speed!=null)speed.removeModifier(SHEATH_SLOW); }
    private static void syncExisting(ServerPlayer recipient) {
        for (ServerPlayer p : recipient.getServer().getPlayerList().getPlayers()) { MutableState s=STATES.get(p.getUUID()); if(s!=null) StyleNetwork.send(recipient,snapshot(p.getUUID(),s)); }
    }

    private static final class DashSweep {
        final ServerLevel level;
        final Vec3 forward;
        final float damage;
        final Set<Entity> hit = new HashSet<>();
        Vec3 lastPosition;
        double speed;
        int stationaryTicks;

        DashSweep(ServerLevel level, Vec3 forward, Vec3 lastPosition, float damage, double speed) {
            this.level = level;
            this.forward = forward;
            this.lastPosition = lastPosition;
            this.damage = damage;
            this.speed = speed;
        }
    }

    private static final class MutableState {
        StyleMode mode;
        String phase; long phaseStart, comboStart; int combo; List<ItemStack> swords; final Vec3 anchor; final boolean airborneEntry;
        long fallingAttackStart = -1, fallingHitStart = -1, fallingLandingExpiry = -1;
        double lastY, fallTopY;
        boolean fallingAttackUsed;
        Entity fallingHitTarget, leapHitTarget;
        long leapHitTick = -1;
        DashSweep dashSweep;
        Entity dashDamageTarget;
        boolean entryLanded;
        MutableState(StyleMode mode, String phase, long start, List<ItemStack> swords, Vec3 anchor, boolean airborneEntry) {
            this.mode = mode;
            this.phase = phase;
            this.phaseStart = start;
            this.swords = swords;
            this.anchor = anchor;
            this.airborneEntry = airborneEntry;
        }
    }
}
