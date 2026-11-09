package fightingarts.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fightingarts.StyleState;
import fightingarts.StyleService;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Small resource-pack animation player for the exported Punchy-compatible clips. */
public final class AnimationRenderer {
    public static final Map<UUID, StyleState> STATES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Clip> CLIPS = new HashMap<>();
    private static final double TRANSITION_TICKS = 2;
    private static final double FIRST_PERSON_TRANSITION_TICKS = 3;
    private static final Map<UUID, Playback> LAST = new HashMap<>();
    private static final Map<AbstractClientPlayer, AirMotion> AIR_MOTION = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, Long> LANDINGS = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, LandingMovement> LANDING_WALK = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, Long> TOUCHDOWNS = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, Pose> LANDING_ARMS = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, Double> GROUND_Y = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, HandGesture> HAND_GESTURES = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, FirstPersonBlend> HAND_BLEND = new WeakHashMap<>();
    private static final Map<UUID, ArmAttacks> ATTACKS = new HashMap<>();
    private static final Map<AbstractClientPlayer, LegCycle> LEG_CYCLES = new WeakHashMap<>();
    private static final Map<String,JsonObject> RIG = new java.util.LinkedHashMap<>();

    private AnimationRenderer() {}

    public static void apply(PlayerModel<?> model, AbstractClientPlayer player, float partialTick) {
        Pose pose = samplePose(player, partialTick);
        if (pose == null) return;
        StyleState state = STATES.get(player.getUUID());
        apply(model, pose, player, state != null && state.phase().equals("ACTIVE"));
        model.hat.copyFrom(model.head);model.jacket.copyFrom(model.body);model.leftSleeve.copyFrom(model.leftArm);model.rightSleeve.copyFrom(model.rightArm);model.leftPants.copyFrom(model.leftLeg);model.rightPants.copyFrom(model.rightLeg);
    }

    /** Refreshes the current player pose for first-person arm and item rendering. */
    public static void updateFirstPerson(AbstractClientPlayer player, float partialTick) {
        samplePose(player, partialTick);
    }

    static void recordAttack(StyleState previous, StyleState current) {
        UUID player = current.playerId();
        if (!current.phase().equals("ACTIVE") || current.combo() == 0) {
            ATTACKS.remove(player);
            return;
        }
        if (previous != null && previous.phase().equals("ACTIVE")
                && previous.combo() == current.combo() && previous.comboStart() == current.comboStart()) return;

        ArmAttacks old = ATTACKS.get(player);
        Attack attack = new Attack(current.combo(), current.comboStart());
        Attack right = current.combo() == 2 ? (old == null ? null : old.right) : attack;
        Attack left = current.combo() == 1 ? null : attack;
        ATTACKS.put(player, new ArmAttacks(right, left));
    }

    static void clearAttacks() {
        ATTACKS.clear();
    }

    /** Samples the authored first-person pose without changing the shared third-person pose. */
    static Pose sampleFirstPersonPose(AbstractClientPlayer player, StyleState state, float partialTick) {
        ensureLoaded();
        long now = player.level().getGameTime();
        double renderTick = now + (double)partialTick;
        FirstPersonBlend playback = HAND_BLEND.computeIfAbsent(player, ignored -> new FirstPersonBlend());
        playback.clock.update(renderTick, player.isCrouching());
        AirMotion air = updateAirMotion(player, now);
        HandGesture gesture = HAND_GESTURES.computeIfAbsent(player, ignored -> new HandGesture());
        if (!state.phase().equals("ACTIVE")) {
            gesture.stop(now);
            Clip sheath = CLIPS.get("fa_fp_embainhar");
            if (!state.phase().equals("SHEATHING") || sheath == null) {
                HAND_BLEND.remove(player);
                return null;
            }
            double progress = Math.clamp(playback.sheath.elapsed(state.phaseStart(), playback.clock) / StyleService.SHEATH_TICKS, 0, 1);
            ArmSource source = new ArmSource("fa_fp_embainhar", state.phaseStart());
            return blendFirstPerson(player, sheath.sampleOnce(progress * sheath.length), source, source, playback.clock.time);
        }
        boolean moving = player.getX() != player.xo || player.getZ() != player.zo;
        String movement = !player.isCrouching() && moving ? (player.isSprinting() ? "fa_fp_run" : "fa_fp_walk") : "fa_fp_idle";
        TimedClip base = air == null
                ? new TimedClip(movement, playback.clock.time / 20, false)
                : air.animation("fa_fp_", playback.air.elapsed(air.stageStart(), playback.clock));
        Clip baseClip = CLIPS.get(base.name);
        ArmAttacks attacks = ATTACKS.get(player.getUUID());
        Attack right = attacks == null ? null : attacks.right;
        Attack left = attacks == null ? null : attacks.left;
        Pose rightPose = right == null ? null
                : sampleAttack(right, true, right.start + playback.rightAttack.elapsed(right.start, playback.clock));
        Pose leftPose = left == null ? null : left.equals(right) ? rightPose
                : sampleAttack(left, true, left.start + playback.leftAttack.elapsed(left.start, playback.clock));
        boolean canPlayGesture = baseClip != null && rightPose == null && leftPose == null;
        boolean falling = air != null && air.fallStart >= 0;
        double gestureTime = gesture.clip == null ? 0 : playback.gesture.elapsed(gesture.start, playback.clock) / 20;
        gesture.update(now, canPlayGesture, falling, gestureTime);
        if (baseClip == null) {
            HAND_BLEND.remove(player);
            return null;
        }
        Pose pose = base.once ? baseClip.sampleOnce(base.time) : baseClip.sample(base.time);
        ArmSource rightSource = new ArmSource(base.name, air == null ? 0 : air.start);
        ArmSource leftSource = rightSource;
        if (rightPose != null || leftPose != null) {
            if (rightPose != null) {
                pose.overlayArm(rightPose, "right_arm", "right_item");
                rightSource = new ArmSource("fa_fp_hitcombo" + right.combo, right.start);
            }
            if (leftPose != null) {
                pose.overlayArm(leftPose, "left_arm", "left_item");
                leftSource = new ArmSource("fa_fp_hitcombo" + left.combo, left.start);
            }
        } else if (gesture.clip != null) {
            Pose overlay = gesture.clip.sampleOnce(playback.gesture.elapsed(gesture.start, playback.clock) / 20);
            ArmSource source = new ArmSource(gesture.left ? "fa_fp_random_left" : "fa_fp_random_right", gesture.start);
            if (gesture.left) {
                pose.overlayArm(overlay, "left_arm", "left_item");
                leftSource = source;
            } else {
                pose.overlayArm(overlay, "right_arm", "right_item");
                rightSource = source;
            }
        }
        return blendFirstPerson(player, pose, rightSource, leftSource, playback.clock.time);
    }

    static void cancelHandGesture(AbstractClientPlayer player) {
        HandGesture gesture = HAND_GESTURES.get(player);
        if (gesture != null) gesture.stop(player.level().getGameTime());
    }

    static void resetHandBlend(AbstractClientPlayer player) {
        HAND_BLEND.remove(player);
    }

    private static Pose sampleAttack(Attack attack, boolean firstPerson, double renderTick) {
        if (attack == null) return null;
        String prefix = firstPerson ? "fa_fp_hitcombo" : "fa_hit_combo";
        Clip clip = CLIPS.get(prefix + attack.combo);
        if (clip == null) return null;
        double time = (renderTick - attack.start) / 20;
        double visualEnd = firstPerson ? Math.min(clip.length, attack.combo == 3 ? 1.92 : 1.13) : clip.length;
        return time >= 0 && time < visualEnd ? clip.sampleOnce(time) : null;
    }

    private static Pose blendFirstPerson(AbstractClientPlayer player, Pose pose, ArmSource right, ArmSource left, double tick) {
        FirstPersonBlend blend = HAND_BLEND.computeIfAbsent(player, ignored -> new FirstPersonBlend());
        blend.right.apply(pose, right, "right_arm", "right_item", tick);
        blend.left.apply(pose, left, "left_arm", "left_item", tick);
        return pose;
    }

    private static String activeComboClip(StyleState state, float time, String prefix) {
        if (state.combo() == 0 || time < 0) return null;
        String name = prefix + state.combo();
        Clip clip = CLIPS.get(name);
        return clip != null && time < clip.length ? name : null;
    }

    private static Pose samplePose(AbstractClientPlayer player, float partialTick) {
        ensureLoaded();
        UUID playerId = player.getUUID();
        StyleState state = STATES.get(playerId);
        if (state == null || state.phase().equals("OFF")) {
            LAST.remove(playerId);
            LEG_CYCLES.remove(player);
            AIR_MOTION.remove(player);
            LANDINGS.remove(player);
            LANDING_WALK.remove(player);
            TOUCHDOWNS.remove(player);
            LANDING_ARMS.remove(player);
            GROUND_Y.remove(player);
            return null;
        }
        long now = player.level().getGameTime();
        double renderTick = now + (double)partialTick;
        String phase = state.phase();
        double ageTicks = renderTick - state.phaseStart();
        double age = ageTicks / 20;
        boolean entry = phase.equals("DRAWING");
        boolean entering = entry && ageTicks < StyleService.DRAW_TICKS;
        float comboTime = (now - state.comboStart() + partialTick) / 20f;
        String combo = phase.equals("ACTIVE") ? activeComboClip(state, comboTime, "fa_hit_combo") : null;
        double speed = Math.hypot(player.getX() - player.xo, player.getZ() - player.zo);
        boolean moving = speed > .001;
        boolean flying = player.getAbilities().flying;
        boolean airborne = !player.onGround() && !flying;
        AirMotion air = updateAirMotion(player, now);
        LegCycle cycle = LEG_CYCLES.computeIfAbsent(player, ignored -> new LegCycle());
        String crouchIdle = wrapCycle(cycle.phase) >= .25 && wrapCycle(cycle.phase) < .75 ? "fa_idle_crouch2" : "fa_idle_crouch";
        String clip = switch (phase) {
            case "DRAWING" -> entering ? "fa_enter_mode" : "fa_idle";
            case "SHEATHING" -> "fa_exit_mode";
            case "ACTIVE" -> combo != null
                    ? combo
                    : player.isCrouching() ? (moving ? "fa_walk_crouch" : crouchIdle) : moving ? (player.isSprinting() ? "fa_run" : walkingBackwards(player) ? "fa_walk_back" : "fa_walk") : "fa_idle";
            default -> player.isCrouching() ? (moving ? "fa_walk_crouch" : crouchIdle) : null;
        };
        double t = combo == null ? age : comboTime;
        boolean action = entry || phase.equals("SHEATHING") || combo != null;
        if (clip == null && flying) clip = "fa_idle";
        if (!action && flying) t = renderTick / 20;
        if ("fa_idle".equals(clip)) {
            if (entry) t = (ageTicks - StyleService.DRAW_TICKS) / 20;
            else if (phase.equals("ACTIVE")) t = (ageTicks + StyleService.ENTRY_TICKS - StyleService.DRAW_TICKS) / 20;
        }
        boolean once = false;
        if (!action && air != null) {
            TimedClip airClip = air.animation("fa_", now, partialTick);
            clip = airClip.name;
            t = airClip.time;
            once = airClip.once;
        }
        Long landing = LANDINGS.get(player);
        double landingAge = landing == null ? -1 : Math.max(0, (renderTick - landing - 2) / 20);
        Clip landingClip = CLIPS.get("fa_fall_ground");
        boolean landingPlaying = !action && air == null && landingAge >= 0 && landingClip != null && landingAge < landingClip.length;
        if (landingPlaying) {
            LandingMovement landingMovement = LANDING_WALK.get(player);
            if (landingMovement != null) landingMovement.update(speed > .02, now);
            clip = landingMovement != null && landingMovement.walking ? "fa_fall_ground_walk_transition" : "fa_fall_ground";
            t = landingAge;
            once = true;
        }
        if (clip == null) return null;
        Clip selected = CLIPS.get(clip);
        if (selected == null) return null;
        Pose legPose = null;
        if (!entry) {
            String gait = player.isCrouching() ? "fa_walk_crouch"
                    : walkingBackwards(player) ? "fa_walk_back" : player.isSprinting() ? "fa_run" : "fa_walk";
            if (landingPlaying && landingAge >= 1.25) gait = player.isSprinting() ? "fa_run" : "fa_walk";
            LegCycle legs = LEG_CYCLES.computeIfAbsent(player, ignored -> new LegCycle());
            legs.update(renderTick, airborne, moving ? gait : null, speed, flying);
            Clip legClip = legs.gait == null ? null : CLIPS.get(legs.gait + "_legs");
            boolean walkingLandingLegs = landingPlaying && moving
                    && "fa_fall_ground_walk_transition".equals(clip) && landingAge >= 1.25;
            if (legClip != null && (!landingPlaying || walkingLandingLegs)) {
                double legTime = wrapCycle(legs.phase) * legClip.length;
                if (clip.equals(legs.gait)) t = legTime;
                else legPose = legClip.sample(legTime);
            }
        } else {
            LEG_CYCLES.remove(player);
        }
        // Hold the last airborne pose during the short re-jump window. Rendering
        // the landing clip at time zero here would still make it flash on screen.
        LandingMovement landingMovement = LANDING_WALK.get(player);
        Pose heldPose = LANDING_ARMS.get(player);
        if (landingPlaying && landingMovement != null && landingMovement.waitForJump
                && renderTick - landing < 2 && heldPose != null) {
            return heldPose;
        }
        Pose pose = once ? selected.sampleOnce(t) : selected.sample(t);
        if (legPose != null) {
            Pose gaitPose = CLIPS.get(LEG_CYCLES.get(player).gait).sample(wrapCycle(LEG_CYCLES.get(player).phase)
                    * CLIPS.get(LEG_CYCLES.get(player).gait).length);
            float blend = landingPlaying ? (float)Math.clamp((landingAge - 1.25) / .25, 0, 1) : 1;
            Pose blendedLegs = landingPlaying ? pose.mix(legPose, blend) : legPose;
            for (String bone : new String[]{"right_leg", "left_leg"}) {
                if (blendedLegs.rotation.containsKey(bone)) pose.rotation.put(bone, blendedLegs.rotation.get(bone));
                if (blendedLegs.position.containsKey(bone)) pose.position.put(bone, blendedLegs.position.get(bone));
                if (blendedLegs.scale.containsKey(bone)) pose.scale.put(bone, blendedLegs.scale.get(bone));
            }
            if (landingPlaying) {
                Pose arms = pose.mix(gaitPose, blend);
                pose.overlayArm(arms, "right_arm", "right_item");
                pose.overlayArm(arms, "left_arm", "left_item");
            }
        }
        if(phase.equals("ACTIVE")){pose.rotation.remove("head");pose.position.remove("head");}
        // Blend clip changes briefly. Filtering every frame delays authored gestures
        // and softens stepped keyframes by a different amount at each frame rate.
        Playback previous = LAST.get(playerId);
        Pose transitionFrom = null;
        double transitionStart = renderTick;
        if (!entering && !(landingPlaying && (previous == null || !previous.clip.startsWith("fa_fall_ground"))) && previous != null
                && renderTick >= previous.renderTick && renderTick - previous.renderTick < TRANSITION_TICKS) {
            if (!clip.equals(previous.clip)) {
                transitionFrom = previous.pose;
            } else if (renderTick - previous.transitionStart < TRANSITION_TICKS) {
                transitionFrom = previous.transitionFrom;
                transitionStart = previous.transitionStart;
            }
        }
        if (transitionFrom != null) {
            pose = transitionFrom.mix(pose, (float)((renderTick - transitionStart) / TRANSITION_TICKS));
        }
        Pose landingArms = LANDING_ARMS.get(player);
        if (landingPlaying && landingArms != null && landingAge < .2) {
            float progress = (float)Math.clamp(landingAge / .2, 0, 1);
            progress = progress * progress * (3 - 2 * progress);
            Pose arms = landingArms.mix(pose, progress);
            pose.overlayArm(arms, "right_arm", "right_item");
            pose.overlayArm(arms, "left_arm", "left_item");
        }
        if ("fa_hit_combo2".equals(combo)) {
            ArmAttacks attacks = ATTACKS.get(playerId);
            if (attacks != null && attacks.right != null && attacks.right.combo == 1) {
                Pose continuingRight = sampleAttack(attacks.right, false, renderTick);
                if (continuingRight != null) pose.overlayArm(continuingRight, "right_arm", "right_item");
            }
        }
        LAST.put(playerId, new Playback(clip, pose, renderTick, transitionFrom, transitionStart));
        return pose;
    }

    private static double wrapCycle(double phase) {
        return phase - Math.floor(phase);
    }

    private static final class LegCycle {
        private String gait;
        private double phase;
        private double previousTick = Double.NaN;
        private double takeoffTick = Double.NaN;
        private double takeoffPhase;
        private double targetPhase;

        private void update(double tick, boolean airborne, String movingGait, double speed, boolean flying) {
            double elapsedTicks = tick - previousTick;
            previousTick = tick;
            if (!airborne) {
                takeoffTick = Double.NaN;
                gait = movingGait;
                Clip clip = gait == null ? null : CLIPS.get(gait + "_legs");
                if (clip != null && elapsedTicks > 0) {
                    double referenceSpeed = switch (gait) {
                        case "fa_run" -> .28;
                        case "fa_walk_crouch" -> .065;
                        default -> .215;
                    };
                    double rate = speed / referenceSpeed;
                    if (flying) rate = Math.min(rate, 1.25);
                    phase = wrapCycle(phase + elapsedTicks * rate / (20 * clip.length));
                }
                return;
            }
            if (Double.isNaN(takeoffTick)) {
                takeoffTick = tick;
                if (gait == null) gait = movingGait;
                takeoffPhase = wrapCycle(phase);
                // 1 and 0 are the same pose, so a phase past .75 targets 1.
                targetPhase = Math.round(takeoffPhase * 2) / 2.0;
            }
            phase = airborneLegPhase(takeoffPhase, targetPhase, Math.max(0, tick - takeoffTick) / 20);
        }
    }

    private static double airborneLegPhase(double from, double target, double seconds) {
        final double approachRate = 2;
        final double driftRate = .12;
        double distance = Math.abs(target - from);
        double approachSeconds = distance / approachRate;
        if (seconds < approachSeconds) return from + Math.copySign(seconds * approachRate, target - from);
        return target + (seconds - approachSeconds) * driftRate;
    }

    private static AirMotion updateAirMotion(AbstractClientPlayer player, long now) {
        if (player.onGround() || player.getAbilities().flying) {
            AirMotion previous = AIR_MOTION.remove(player);
            if (previous != null && previous.fallStart >= 0 && !player.getAbilities().flying) TOUCHDOWNS.put(player, now);
            if (previous != null && previous.fallStart >= 0 && !player.getAbilities().flying
                    && previous.highestY - player.getY() >= 1.0 - 1e-3) {
                LANDINGS.put(player, now);
                double groundSpeed = Math.hypot(player.getX() - player.xo, player.getZ() - player.zo);
                LANDING_WALK.put(player, new LandingMovement(previous.horizontalSpeed > .02 || groundSpeed > .02,
                        previous.sprinting || player.isSprinting(), now));
                Playback previousPose = LAST.get(player.getUUID());
                if (previousPose != null) LANDING_ARMS.put(player, previousPose.pose);
                else LANDING_ARMS.remove(player);
            }
            GROUND_Y.put(player, player.getY());
            return null;
        }
        LANDINGS.remove(player);
        LANDING_WALK.remove(player);
        LANDING_ARMS.remove(player);
        Long touchdown = TOUCHDOWNS.remove(player);
        if (touchdown != null && now - touchdown <= 2) {
            LegCycle legs = LEG_CYCLES.get(player);
            if (legs != null) legs.phase = Math.floor(wrapCycle(legs.phase) * 2 + .001) / 2 + .5;
        }
        double velocity = player.getDeltaMovement().y;
        AirMotion air = AIR_MOTION.computeIfAbsent(player, ignored -> new AirMotion(now, velocity, player.getGravity(),
                Math.max(player.getY(), GROUND_Y.getOrDefault(player, player.getY()))));
        air.highestY = Math.max(air.highestY, player.getY());
        air.horizontalSpeed = Math.hypot(player.getX() - player.xo, player.getZ() - player.zo);
        air.sprinting = player.isSprinting();
        if (velocity <= 0 && air.fallStart < 0) air.fallStart = now;
        return air;
    }

    public static void tickMotion(AbstractClientPlayer player) {
        StyleState state = STATES.get(player.getUUID());
        if (state != null && state.phase().equals("ACTIVE")) updateAirMotion(player, player.level().getGameTime());
        else { AIR_MOTION.remove(player); LANDINGS.remove(player); LANDING_WALK.remove(player); LANDING_ARMS.remove(player); TOUCHDOWNS.remove(player); GROUND_Y.remove(player); }
    }

    public static double landingCameraOffset(AbstractClientPlayer player, float partialTick) {
        Long landed = LANDINGS.get(player);
        if (landed == null || !player.onGround()) return 0;
        double seconds = (player.level().getGameTime() + partialTick - landed - 2) / 20;
        Clip clip = CLIPS.get("fa_fall_ground");
        double duration = clip == null ? 1.5 : clip.length;
        return seconds < 0 || seconds >= duration ? 0 : -.09 * Math.sin(Math.PI * seconds / duration);
    }

    private record TimedClip(String name, double time, boolean once) {}
    private static final class LandingMovement {
        private boolean walking;
        private boolean candidate;
        private final boolean waitForJump;
        private long candidateSince;

        private LandingMovement(boolean walking, boolean sprinting, long now) {
            this.walking = walking;
            this.waitForJump = walking && sprinting;
            this.candidate = walking;
            this.candidateSince = now;
        }

        private void update(boolean moving, long now) {
            if (moving != candidate) { candidate = moving; candidateSince = now; }
            if (now - candidateSince >= 2) walking = candidate;
        }
    }
    private record Attack(int combo, long start) {}
    private record ArmAttacks(Attack right, Attack left) {}
    private record ArmSource(String clip, long start) {}

    private static final class FirstPersonBlend {
        private final HandBlend right = new HandBlend();
        private final HandBlend left = new HandBlend();
        private final FirstPersonClock clock = new FirstPersonClock();
        private final ClipTimer rightAttack = new ClipTimer();
        private final ClipTimer leftAttack = new ClipTimer();
        private final ClipTimer sheath = new ClipTimer();
        private final ClipTimer air = new ClipTimer();
        private final ClipTimer gesture = new ClipTimer();
    }

    private static final class FirstPersonClock {
        private double tick = Double.NaN;
        private double time;
        private double rate = 1;

        private void update(double tick, boolean crouching) {
            rate = crouching ? .5 : 1;
            if (Double.isNaN(this.tick)) {
                this.tick = tick;
                time = tick;
            } else if (tick > this.tick) {
                time += (tick - this.tick) * rate;
                this.tick = tick;
            }
        }
    }

    private static final class ClipTimer {
        private long start = Long.MIN_VALUE;
        private double startTime;

        private double elapsed(long start, FirstPersonClock clock) {
            if (this.start != start) {
                this.start = start;
                startTime = clock.time - (clock.tick - start) * clock.rate;
            }
            return clock.time - startTime;
        }
    }

    private static final class HandBlend {
        private ArmSource source;
        private Pose previous;
        private Pose from;
        private double started;
        private double lastTick;

        private void apply(Pose pose, ArmSource target, String arm, String item, double tick) {
            if (previous == null || tick < lastTick || tick - lastTick > FIRST_PERSON_TRANSITION_TICKS) {
                from = null;
            } else if (!target.equals(source)) {
                from = previous;
                started = tick;
            }
            Pose hand = new Pose(new HashMap<>(), new HashMap<>(), new HashMap<>());
            hand.overlayArm(pose, arm, item);
            double progress = (tick - started) / FIRST_PERSON_TRANSITION_TICKS;
            if (from != null && progress < 1) {
                float eased = (float)(progress * progress * (3 - 2 * progress));
                hand = from.mix(hand, eased);
                pose.overlayArm(hand, arm, item);
            } else {
                from = null;
            }
            previous = hand;
            source = target;
            lastTick = tick;
        }
    }

    private static final class AirMotion {
        private final long start;
        private final int ascentTicks;
        private double highestY;
        private double horizontalSpeed;
        private boolean sprinting;
        private long fallStart = -1;

        private AirMotion(long start, double velocity, double gravity, double y) {
            this.start = start;
            this.highestY = y;
            int ticks = 0;
            while (velocity > 0 && ticks < 200) {
                velocity = (velocity - gravity) * .98;
                ticks++;
            }
            ascentTicks = Math.max(1, ticks);
        }

        private TimedClip animation(String prefix, long now, float partialTick) {
            return animation(prefix, now - stageStart() + partialTick);
        }

        private long stageStart() {
            return fallStart < 0 ? start : fallStart;
        }

        private TimedClip animation(String prefix, double elapsedTicks) {
            if (fallStart < 0) {
                String name = prefix + "jump";
                Clip jump = CLIPS.get(name);
                double progress = Math.clamp(elapsedTicks / ascentTicks, 0, 1);
                return new TimedClip(name, jump == null ? 0 : jump.length * progress, true);
            }
            String name = prefix + "fall";
            Clip fall = CLIPS.get(name);
            double age = Math.max(0, elapsedTicks / 20);
            if (fall != null && age < fall.length) return new TimedClip(name, age, true);
            return new TimedClip(prefix + "fall_loop", age - (fall == null ? 0 : fall.length), false);
        }
    }

    private static final class HandGesture {
        private static final int MIN_GAP_TICKS = 60 * 20;
        private Clip clip;
        private boolean left;
        private long start;
        private long next;

        private void update(long now, boolean canPlay, boolean falling, double elapsedSeconds) {
            if (next == 0) next = now + randomGap();
            if (clip != null && (!canPlay || elapsedSeconds >= clip.length)) stop(now);
            if (!canPlay || falling || clip != null || now < next) return;

            left = ThreadLocalRandom.current().nextBoolean();
            clip = CLIPS.get(left ? "fa_fp_random_left" : "fa_fp_random_right");
            start = now;
            if (clip == null) next = now + randomGap();
        }

        private void stop(long now) {
            if (clip == null) return;
            clip = null;
            next = now + randomGap();
        }

        private static int randomGap() {
            return MIN_GAP_TICKS + ThreadLocalRandom.current().nextInt(MIN_GAP_TICKS + 1);
        }
    }

    private static boolean walkingBackwards(Player player) {
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        double yaw = Math.toRadians(player.getYRot());
        return dx * -Math.sin(yaw) + dz * Math.cos(yaw) < -.001;
    }

    private static void ensureLoaded() {
        if (!CLIPS.isEmpty()) return;
        try {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            try(var rigReader=new InputStreamReader(minecraft.getResourceManager().getResourceOrThrow(ResourceLocation.fromNamespaceAndPath("fightingarts","styles/santoryu_rig.json")).open(),StandardCharsets.UTF_8)){for(var g:JsonParser.parseReader(rigReader).getAsJsonObject().getAsJsonArray("groups")){JsonObject o=g.getAsJsonObject();RIG.put(o.get("name").getAsString(),o);}}
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("fightingarts", "animations/santoryu.json");
            Resource resource = minecraft.getResourceManager().getResourceOrThrow(id);
            try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject animations = root.getAsJsonObject("animations");
                for (var entry : animations.entrySet()) CLIPS.put(entry.getKey(), Clip.read(entry.getValue().getAsJsonObject()));
            }
        } catch (Exception ignored) { }
    }

    private static void apply(PlayerModel<?> m, Pose p, Player player, boolean combat) {
        float lookX=m.head.xRot,lookY=m.head.yRot;
        // The authored pose replaces vanilla locomotion, swing, crouch and riding rotations.
        for(var part:java.util.List.of(m.body,m.head,m.rightArm,m.leftArm,m.rightLeg,m.leftLeg))part.resetPose();
        if(combat){m.head.xRot=lookX;m.head.yRot=lookY;}
        Map<String,org.joml.Matrix4f> worlds=new HashMap<>(),bind=new HashMap<>();
        Pose rest=new Pose(Map.of(),Map.of(),Map.of());
        for(String name:RIG.keySet()){world(name,p,worlds);world(name,rest,bind);}
        modelBone(m.body,"torso",worlds,bind);modelBone(m.head,"head",worlds,bind);modelBone(m.rightArm,"right_arm",worlds,bind);modelBone(m.leftArm,"left_arm",worlds,bind);modelBone(m.rightLeg,"right_leg",worlds,bind);modelBone(m.leftLeg,"left_leg",worlds,bind);
    }

    private static org.joml.Matrix4f world(String name,Pose p,Map<String,org.joml.Matrix4f> cache){
        if(cache.containsKey(name))return cache.get(name);JsonObject g=RIG.get(name);String parent=g.get("parent").isJsonNull()?null:g.get("parent").getAsString();
        float[] origin=Clip.vec(g.get("origin")),base=Clip.vec(g.get("rotation"));float[] parentOrigin=parent==null?ZERO:Clip.vec(RIG.get(parent).get("origin"));
        String track=name.equals("body")?"root":name.equals("torso")?"body":name;
        float[] x=p.position.getOrDefault(track,ZERO),r=p.rotation.getOrDefault(track,ZERO),s=p.scale.getOrDefault(track,ONE);
        org.joml.Matrix4f mat=parent==null?new org.joml.Matrix4f():new org.joml.Matrix4f(world(parent,p,cache));
        mat.translate(origin[0]-parentOrigin[0]+x[0],origin[1]-parentOrigin[1]+x[1],origin[2]-parentOrigin[2]+x[2]);
        mat.rotateZYX((base[2]+r[2])*Mth.DEG_TO_RAD,(base[1]+r[1])*Mth.DEG_TO_RAD,(base[0]+r[0])*Mth.DEG_TO_RAD).scale(s[0],s[1],s[2]);cache.put(name,mat);return mat;
    }
    private static void modelBone(net.minecraft.client.model.geom.ModelPart part,String name,Map<String,org.joml.Matrix4f> worlds,Map<String,org.joml.Matrix4f> bind){
        org.joml.Matrix4f w=worlds.get(name),b=bind.get(name);if(w==null||b==null)return;
        // Apply the Blockbench rig's animated world delta to Minecraft's native bind pose.
        // Their rigs have different pivots (notably torso), so copying BB world origins to ModelPart
        // positions directly displaces the whole body by 12 model units.
        org.joml.Matrix4f delta=new org.joml.Matrix4f(w).mul(b.invert(new org.joml.Matrix4f()));
        // Blockbench is Y-up and centered at Y=24; PlayerModel is Y-down and
        // centered at the top of the player. Convert the complete coordinate
        // frame, including its origin, when moving a world-space rig delta.
        org.joml.Matrix4f bbToMinecraft=new org.joml.Matrix4f()
                .translation(0,24,0).rotateZ((float)Math.PI);
        org.joml.Matrix4f mapped=new org.joml.Matrix4f(bbToMinecraft).mul(delta)
                .mul(bbToMinecraft.invert(new org.joml.Matrix4f()));
        // Slim skins lower the vanilla shoulder by half a model unit. Use the
        // authored shoulder height while keeping the skin's own arm geometry.
        float bindY = name.endsWith("_arm") ? bbToMinecraft.m31() - b.m31() : part.y;
        org.joml.Matrix4f nativePose=new org.joml.Matrix4f().translation(part.x,bindY,part.z)
                .rotateZYX(part.zRot,part.yRot,part.xRot).scale(part.xScale,part.yScale,part.zScale);
        org.joml.Matrix4f result=mapped.mul(nativePose);
        org.joml.Vector3f pos=result.getTranslation(new org.joml.Vector3f()),scale=result.getScale(new org.joml.Vector3f());
        org.joml.Quaternionf q = result.getUnnormalizedRotation(new org.joml.Quaternionf()).normalize();
        // JOML 1.10.5's quaternion-to-ZYX conversion uses the wrong denominator for X.
        org.joml.Matrix4f rotation = result.rotation(q);
        org.joml.Vector3f r = rotation.getEulerAnglesZYX(new org.joml.Vector3f());
        if (Math.abs(rotation.m02()) > .9999999f) {
            r.x = 0;
            r.z = (float)Math.atan2(-rotation.m10(), rotation.m11());
        }
        part.setPos(pos.x,pos.y,pos.z);part.setRotation(r.x,r.y,r.z);part.xScale=scale.x;part.yScale=scale.y;part.zScale=scale.z;
    }

    private static void bone(net.minecraft.client.model.geom.ModelPart part, Pose pose, String name) {
        float[] r = pose.rotation.getOrDefault(name, ZERO);
        float[] x = pose.position.getOrDefault(name, ZERO);
        if(!name.equals("head")){part.xRot=r[0]*Mth.DEG_TO_RAD;part.yRot=r[1]*Mth.DEG_TO_RAD;part.zRot=r[2]*Mth.DEG_TO_RAD;}
        else {part.xRot+=r[0]*Mth.DEG_TO_RAD;part.yRot+=r[1]*Mth.DEG_TO_RAD;part.zRot+=r[2]*Mth.DEG_TO_RAD;}
        part.x += x[0]; part.y -= x[1]; part.z += x[2];
        float[] s=pose.scale.getOrDefault(name,ONE);part.xScale=s[0];part.yScale=s[1];part.zScale=s[2];
    }

    private static final float[] ZERO = {0,0,0};
    private static final float[] ONE = {1,1,1};
    public static float[] transform(UUID player, String bone) {
        Playback playback = LAST.get(player);
        return playback == null ? new float[]{0,0,0,0,0,0,1,1,1} : playback.pose.transform(bone);
    }

    private record Playback(String clip, Pose pose, double renderTick, Pose transitionFrom, double transitionStart) {}
    private record Clip(float length, boolean loop, Map<String,Map<Float,float[]>> rotation, Map<String,Map<Float,float[]>> position,Map<String,Map<Float,float[]>> scale) {
        static Clip read(JsonObject o) {
            float len=o.has("animation_length")?o.get("animation_length").getAsFloat():1;
            boolean loop=o.has("loop")&&o.get("loop").getAsBoolean();
            Map<String,Map<Float,float[]>> r=new HashMap<>(),p=new HashMap<>(),s=new HashMap<>();
            if(o.has("bones")) for(var e:o.getAsJsonObject("bones").entrySet()) {
                JsonObject b=e.getValue().getAsJsonObject();
                if(b.has("rotation")) r.put(e.getKey(), channel(b.get("rotation")));
                if(b.has("position")) p.put(e.getKey(), channel(b.get("position")));
                if(b.has("scale")) s.put(e.getKey(),channel(b.get("scale")));
            }
            return new Clip(len,loop,r,p,s);
        }
        static Map<Float,float[]> channel(JsonElement e) {
            Map<Float,float[]> m=new java.util.TreeMap<>();
            if(e.isJsonArray()) m.put(0f,vec(e));
            else if(e.isJsonObject()) for(var k:e.getAsJsonObject().entrySet()) {
                try { JsonElement v=k.getValue(); if(v.isJsonObject()) v=v.getAsJsonObject().has("post")?v.getAsJsonObject().get("post"):v.getAsJsonObject().get("vector"); m.put(Float.parseFloat(k.getKey()),vec(v)); } catch(Exception ignored){}
            }
            return m;
        }
        static float[] vec(JsonElement e) { return new float[]{e.getAsJsonArray().get(0).getAsFloat(),e.getAsJsonArray().get(1).getAsFloat(),e.getAsJsonArray().get(2).getAsFloat()}; }
        Pose sample(double time) {
            return sampleAt((float)(loop && length > 0 ? time % length : Math.min(time, length)));
        }
        Pose sampleOnce(double time) {
            return sampleAt((float)Math.clamp(time, 0, length));
        }
        private Pose sampleAt(float t) {
            Map<String,float[]> rr=new HashMap<>(),pp=new HashMap<>(),ss=new HashMap<>();
            rotation.forEach((b,k)->rr.put(b,at(k,t))); position.forEach((b,k)->pp.put(b,at(k,t)));
            scale.forEach((b,k)->ss.put(b,at(k,t)));return new Pose(rr,pp,ss);
        }
        static float[] at(Map<Float,float[]> keys,float t) {
            if(keys.isEmpty())return ZERO; var lo=keys.entrySet().stream().filter(e->e.getKey()<=t).reduce((a,b)->b).orElse(null); var hi=keys.entrySet().stream().filter(e->e.getKey()>=t).findFirst().orElse(null);
            if(lo==null)return hi.getValue(); if(hi==null||lo==hi)return lo.getValue(); float f=(t-lo.getKey())/(hi.getKey()-lo.getKey()); float[] out=new float[3]; for(int i=0;i<3;i++) out[i]=Mth.lerp(f,lo.getValue()[i],hi.getValue()[i]); return out;
        }
    }
    record Pose(Map<String,float[]> rotation,Map<String,float[]> position,Map<String,float[]> scale) {
        void overlayArm(Pose source, String arm, String item) {
            for (String bone : new String[]{arm, item}) {
                if (source.rotation.containsKey(bone)) rotation.put(bone, source.rotation.get(bone));
                if (source.position.containsKey(bone)) position.put(bone, source.position.get(bone));
                if (source.scale.containsKey(bone)) scale.put(bone, source.scale.get(bone));
            }
        }

        float[] transform(String bone) {
            float[] p = position.getOrDefault(bone, ZERO);
            float[] r = rotation.getOrDefault(bone, ZERO);
            float[] s = scale.getOrDefault(bone, ONE);
            return new float[]{p[0],p[1],p[2],r[0],r[1],r[2],s[0],s[1],s[2]};
        }

        Pose mix(Pose b,float f) {
            Map<String,float[]> r=new HashMap<>(),p=new HashMap<>();
            java.util.Set<String> names=new java.util.HashSet<>(rotation.keySet()); names.addAll(b.rotation.keySet());
            for(String n:names) r.put(n,blendAngles(rotation.getOrDefault(n,ZERO),b.rotation.getOrDefault(n,ZERO),f));
            names=new java.util.HashSet<>(position.keySet()); names.addAll(b.position.keySet());
            for(String n:names) p.put(n,blend(position.getOrDefault(n,ZERO),b.position.getOrDefault(n,ZERO),f));
            Map<String,float[]> s=new HashMap<>(); names=new java.util.HashSet<>(scale.keySet());names.addAll(b.scale.keySet());for(String n:names)s.put(n,blend(scale.getOrDefault(n,ONE),b.scale.getOrDefault(n,ONE),f));
            return new Pose(r,p,s);
        }
        static float[] blend(float[] a,float[] b,float f) { return new float[]{Mth.lerp(f,a[0],b[0]),Mth.lerp(f,a[1],b[1]),Mth.lerp(f,a[2],b[2])}; }
        static float[] blendAngles(float[] a,float[] b,float f) {
            float[] out=new float[3];
            for(int i=0;i<3;i++)out[i]=a[i]+Mth.wrapDegrees(b[i]-a[i])*f;
            return out;
        }
    }
}
