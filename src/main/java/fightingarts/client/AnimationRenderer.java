package fightingarts.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fightingarts.StyleState;
import fightingarts.SantoryuSkills;
import fightingarts.StyleDefense;
import fightingarts.StyleMode;
import fightingarts.StyleService;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Small resource-pack animation player for the exported Punchy-compatible clips. */
public final class AnimationRenderer {
    public static final Map<UUID, StyleState> STATES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Clip> CLIPS = new HashMap<>();
    private static final double TRANSITION_TICKS = 2;
    private static final double FIRST_PERSON_TRANSITION_TICKS = 3;
    private static final double FIRST_PERSON_FALLING_ENTRY_TICKS = 5;
    private static final double FIRST_PERSON_FALLING_HIT_END_SECONDS = 1.75;
    private static final Map<UUID, Playback> LAST = new HashMap<>();
    private static final Map<AbstractClientPlayer, FreeArmBlends> FREE_ARM_BLENDS = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, JumpRotation> JUMP_ROTATION=new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, JumpRotation> FP_JUMP_ROTATION=new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, AirMotion> AIR_MOTION = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, Long> LANDINGS = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, LandingMovement> LANDING_WALK = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, Long> TOUCHDOWNS = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, Pose> LANDING_ARMS = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, EntryLanding> ENTRY_LANDINGS = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, Double> GROUND_Y = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, HandGesture> HAND_GESTURES = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, FirstPersonBlend> HAND_BLEND = new WeakHashMap<>();
    private static final Map<AbstractClientPlayer, FirstPersonStrikes> FP_STRIKES = new WeakHashMap<>();
    private static final Map<UUID, ArmAttacks> ATTACKS = new HashMap<>();
    private static final Map<UUID, Attack> LANDING_INTERRUPTED = new HashMap<>();
    private static final Map<AbstractClientPlayer, LegCycle> LEG_CYCLES = new WeakHashMap<>();
    private static final Map<String,JsonObject> RIG = new java.util.LinkedHashMap<>();

    private AnimationRenderer() {}

    public static void apply(PlayerModel<?> model, AbstractClientPlayer player, float partialTick) {
        Pose pose = samplePose(player, partialTick);
        if (pose == null) return;
        StyleState state = STATES.get(player.getUUID());
        apply(model, pose, player, state != null && state.phase().equals("ACTIVE"));
        if (state != null && state.phase().equals("ACTIVE")) {
            var consumption = ConsumableClient.frame(player, partialTick);
            if (consumption != null) {
                float draw = consumption.draw();
                float bite = (float)Math.sin(consumption.progress() * Math.PI * 10) * .06f;
                model.leftArm.xRot = Mth.lerp(draw, model.leftArm.xRot, -1.82f + .35f * model.head.xRot + bite);
                model.leftArm.yRot = Mth.lerp(draw, model.leftArm.yRot, .4f);
                model.leftArm.zRot = Mth.lerp(draw, model.leftArm.zRot, -.32f);
            }
        }
        var defense = state == null ? null : DefenseClient.pose(player, state);
        if (defense != null) {
            float weight = (float) Math.clamp(defense.raiseProgress(player.level().getGameTime() + (double) partialTick) * 2, 0, 1);
            float pitch = model.head.xRot * weight;
            followHeadPitch(model.rightArm, pitch);
            followHeadPitch(model.leftArm, pitch);
        }
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
        Attack left = current.combo() == 1 ? (old == null ? null : old.left) : attack;
        ATTACKS.put(player, new ArmAttacks(right, left));
    }

    static void clearAttacks() {
        ATTACKS.clear();
        FP_STRIKES.clear();
        LANDING_INTERRUPTED.clear();
    }

    static void recordFirstPersonAttack(AbstractClientPlayer player, StyleState previous, StyleState current) {
        if (!current.phase().equals("ACTIVE")) {
            FP_STRIKES.remove(player);
            return;
        }
        if (current.fallingHitStart() >= 0 && (previous == null || current.fallingHitStart() != previous.fallingHitStart())) {
            FP_STRIKES.computeIfAbsent(player, ignored -> new FirstPersonStrikes()).add(new Attack(4, current.fallingHitStart()));
        } else if (current.combo() != 0 && (previous == null || previous.combo() != current.combo()
                || previous.comboStart() != current.comboStart())) {
            FP_STRIKES.computeIfAbsent(player, ignored -> new FirstPersonStrikes()).add(new Attack(current.combo(), current.comboStart()));
        }
    }

    static boolean firstPersonStriking(AbstractClientPlayer player, boolean left) {
        var strikes = FP_STRIKES.get(player);
        return strikes != null && strikes.playing(left) != null;
    }

    static boolean firstPersonHasStrike(AbstractClientPlayer player) {
        var strikes = FP_STRIKES.get(player);
        return strikes != null && (!strikes.pending.isEmpty() || strikes.right != null || strikes.left != null);
    }

    static void clearFirstPersonStrikes(AbstractClientPlayer player) {
        FP_STRIKES.remove(player);
    }

    static void entryStateChanged(AbstractClientPlayer player, StyleState previous, StyleState current) {
        if (previous != null && previous.phase().equals("DRAWING") && current.phase().equals("DRAWING")
                && previous.phaseStart() == current.phaseStart() && previous.entryAirborne() && !current.entryAirborne()
                && !ENTRY_LANDINGS.containsKey(player)) {
            Playback last = LAST.get(player.getUUID());
            ENTRY_LANDINGS.put(player, new EntryLanding(player.level().getGameTime(), current.phaseStart(),
                    last == null ? null : last.pose));
        }
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
            boolean simpleDraw = state.phase().equals(StyleService.SIMPLE_DRAWING);
            boolean simpleSheath = state.phase().equals("SHEATHING") && state.mode() == StyleMode.SIMPLE;
            if (simpleDraw || simpleSheath || state.phase().equals("UPGRADING")) {
                String name = state.phase().equals("UPGRADING") ? "fa_enter_mode_common2" : simpleDraw ? "fa_enter_mode_simple" : "fa_exit_mode_simple";
                Clip clip = CLIPS.get(name);
                if (clip == null) return null;
                ArmSource source = new ArmSource(name, state.phaseStart());
                double elapsed = renderTick - state.phaseStart();
                Pose pose = clip.sampleOnce(state.phase().equals("UPGRADING")
                        ? StyleService.upgradeAnimationSeconds(elapsed) : elapsed / 20);
                return blendFirstPerson(player, pose, source, source, renderTick);
            }
            Clip sheath = CLIPS.get("fa_fp_embainhar");
            if (!state.phase().equals("SHEATHING") || sheath == null) {
                HAND_BLEND.remove(player);
                return null;
            }
            double progress = Math.clamp(playback.sheath.elapsed(state.phaseStart(), playback.clock) / StyleService.SHEATH_TICKS, 0, 1);
            ArmSource source = new ArmSource("fa_fp_embainhar", state.phaseStart());
            return blendFirstPerson(player, sheath.sampleOnce(progress * sheath.length), source, source, renderTick);
        }
        boolean moving = player.getX() != player.xo || player.getZ() != player.zo;
        String movement = !player.isCrouching() && moving ? (player.isSprinting() ? "fa_fp_run" : "fa_fp_walk") : "fa_fp_idle";
        TimedClip fallingOverlay = state.fallingAttackStart() < 0 ? null
                : fallingAttackAnimation("fa_fp_", renderTick - state.fallingAttackStart());
        TimedClip base = air == null
                ? new TimedClip(movement, playback.clock.time / 20, false)
                : air.animation("fa_fp_", playback.air.elapsed(air.stageStart(), playback.clock));
        Clip baseClip = CLIPS.get(base.name);
        var skill = SkillHud.STATUS.get(player.getUUID());
        var guard = DefenseClient.pose(player, state);
        var strikes = FP_STRIKES.get(player);
        if (strikes != null) {
            long interruption = guard != null && guard.held() ? guard.raisedAt() : Long.MIN_VALUE;
            if (skill != null && (skill.skill() == 1 || skill.skill() == 2 || skill.skill() == 3 || skill.skill() == 7)) {
                interruption = Math.max(interruption, skill.start());
            }
            strikes.advance(renderTick, interruption);
        }
        var right = strikes == null ? null : strikes.playing(false);
        var left = strikes == null ? null : strikes.playing(true);
        boolean striking = right != null || left != null;
        boolean canPlayGesture = fallingOverlay == null && baseClip != null && !striking;
        boolean falling = air != null && air.fallStart >= 0;
        double gestureTime = gesture.clip == null ? 0 : playback.gesture.elapsed(gesture.start, playback.clock) / 20;
        gesture.update(now, canPlayGesture, falling, gestureTime);
        Pose pose = baseClip == null ? new Pose(new HashMap<>(), new HashMap<>(), new HashMap<>())
                : base.once ? baseClip.sampleOnce(base.time) : baseClip.sample(base.time);
        if(baseClip != null && base.name.equals("fa_fp_jump"))sampleJumpRotations(pose,baseClip,base.time);
        ArmSource rightSource = new ArmSource(base.name, air == null ? 0 : air.start);
        ArmSource leftSource = rightSource;
        if (fallingOverlay != null) {
            Clip overlayClip = CLIPS.get(fallingOverlay.name);
            if (overlayClip != null) {
                Pose overlay = fallingOverlay.once ? overlayClip.sampleOnce(fallingOverlay.time) : overlayClip.sample(fallingOverlay.time);
                overlayFallingArms(pose, overlay);
                FirstPersonStyleRenderer.positionFallingArms(pose);
                rightSource = leftSource = new ArmSource(fallingOverlay.name, state.fallingAttackStart());
            }
        }
        if (!striking && fallingOverlay == null && gesture.clip != null) {
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
        pose = skillPose(pose, skill, renderTick, true);
        if (skill != null && skill.skill() != 0 && skill.skill() != 5 && skill.skill() != 6) rightSource = leftSource = new ArmSource("skill:" + skill.skill() + ":" + (renderTick-skill.start()<skill.preparationTicks()), skill.start());
        Pose defense = defensePose(player, state, pose, renderTick, true);
        if (defense != pose && !striking) {
            FP_JUMP_ROTATION.remove(player);
            playback.right.capture(defense, "right_arm", "right_item", renderTick);
            playback.left.capture(defense, "left_arm", "left_item", renderTick);
            return defense;
        }
        pose = defense;
        if (right != null) rightSource = applyStrike(pose, strikes, right, "right_arm", "right_item");
        if (left != null) leftSource = applyStrike(pose, strikes, left, "left_arm", "left_item");
        pose=blendFirstPerson(player,pose,rightSource,leftSource,renderTick);
        boolean locomotion=!striking && fallingOverlay==null
                && (skill==null || skill.skill()==0 || skill.skill()==4) && visionClip(skill,player,renderTick)==null;
        smoothJumpRotation(FP_JUMP_ROTATION,player,pose,locomotion,air!=null,renderTick,2*FIRST_PERSON_TRANSITION_TICKS);
        return pose;
    }

    private static ArmSource applyStrike(Pose pose, FirstPersonStrikes playback, PlayingStrike swing, String arm, String item) {
        String name = swing.clipName();
        Pose action = CLIPS.get(name).sampleOnce((playback.time - swing.started) / 20);
        if (swing.attack.combo == 4) FirstPersonStyleRenderer.positionFallingArms(action);
        pose.overlayArm(action, arm, item);
        return new ArmSource(name, swing.attack.start);
    }

    private static Pose defensePose(AbstractClientPlayer player, StyleState style, Pose neutral,
                                    double tick, boolean firstPerson) {
        var guard = DefenseClient.pose(player, style);
        if (guard == null) return neutral;
        double progress = guard.raiseProgress(tick);
        if (progress <= 0) return neutral;
        String prefix = firstPerson ? "fa_fp_defend_" : "fa_defend_";
        Clip start = CLIPS.get(prefix + "start"), loop = CLIPS.get(prefix + "loop");
        if (start == null || loop == null) return neutral;

        Pose guarding = start.sampleOnce(progress * start.length);
        double loopTime = (tick - guard.raisedAt() - StyleDefense.RAISE_TICKS) / 20;
        if (guard.held() && loopTime >= 0) {
            Pose cycle = loop.sample(loopTime);
            // The FP loop only keys the arms, so keep the sword transforms from the raise clip.
            guarding.rotation.putAll(cycle.rotation);
            guarding.position.putAll(cycle.position);
            guarding.scale.putAll(cycle.scale);
        } else if (!guard.held()) {
            double heldLoopTime = (guard.releasedAt() - guard.raisedAt() - StyleDefense.RAISE_TICKS) / 20.0;
            double releaseSeconds = Math.max(0, tick - guard.releasedAt()) / 20;
            if (heldLoopTime > 0 && releaseSeconds < .08) {
                Pose previous = start.sampleOnce(start.length);
                Pose cycle = loop.sample(heldLoopTime);
                previous.rotation.putAll(cycle.rotation);
                previous.position.putAll(cycle.position);
                previous.scale.putAll(cycle.scale);
                guarding = previous.mix(guarding, (float) (releaseSeconds / .08));
            }
        }
        Pose result = neutral.mix(guarding, (float) Math.min(1, progress * 4));
        if (!firstPerson) {
            // The mouth attachment inherits the torso and head through the authored rig.
            for (String bone : List.of("root","body","head","right_item2")) {
                result.rotation.remove(bone);
                result.position.remove(bone);
                result.scale.remove(bone);
                result.overlayChannels(neutral, bone, Pose.ALL, Pose.ALL, Pose.ALL);
            }
        }
        return result;
    }

    static Pose firstPersonIdlePose() {
        Clip idle = CLIPS.get("fa_fp_idle");
        return idle == null ? null : idle.sample(0);
    }

    private static String visionClip(SantoryuSkills.Status skill,AbstractClientPlayer player,double tick){
        if(skill==null || player==null)return null;
        if(skill.visionStart()>=0){
            if(tick-skill.visionStart()<20)return "fa_skill1_enter";
            return player.walkAnimation.speed()>.04f?"fa_skill1_walk":"fa_skill1_idle";
        }
        if(skill.visionEnd()>=0 && tick>=skill.visionEnd() && tick-skill.visionEnd()<skill.visionExitTicks())
            return "reverse:fa_skill1_enter";
        return null;
    }

    private static Pose skillPose(Pose base, SantoryuSkills.Status skill, double tick, boolean firstPerson) {
        if (skill == null) return base;
        double age = (tick - skill.start()) / 20;
        var skillPlayer = net.minecraft.client.Minecraft.getInstance().level == null ? null
                : net.minecraft.client.Minecraft.getInstance().level.getPlayerByUUID(skill.player());
        var state=skillPlayer==null?null:STATES.get(skill.player());
        boolean striking=state!=null && activeComboClip(state,(float)((tick-state.comboStart())/20),"fa_hit_combo")!=null;
        String visionName=visionClip(skill,skillPlayer instanceof AbstractClientPlayer client?client:null,tick);
        if(visionName!=null && !striking && (skill.skill()==0 || skill.skill()==6)){
            if(visionName.startsWith("reverse:")){
                Clip entrance=CLIPS.get("fa_skill1_enter");
                if(entrance!=null){
                    double remaining=skill.visionExitTicks()-(tick-skill.visionEnd());
                    Pose reverse=entrance.sampleOnce(entrance.length*Math.clamp(remaining/20,0,1));
                    if(!firstPerson)base=reverse;
                    else {
                        Pose eased=base.mix(reverse,(float)AttackHotbar.ease(Math.min(1,remaining/5)));
                        for(String bone:new String[]{"right_arm","left_arm"})base.overlayChannels(eased,bone,Pose.ALL,0,0);
                    }
                }
            }else if(!firstPerson){
                Clip vision=CLIPS.get(visionName);
                if(vision!=null){
                    double seconds=Math.max(0,(tick-skill.visionStart())/20);
                    base=visionName.equals("fa_skill1_enter")?vision.sampleOnce(seconds):vision.sample((seconds-1)%vision.length);
                }
            }
        }
        if(skill.skill()==7){
            Clip cast=CLIPS.get("fa_skill2");
            if(cast!=null){
                Pose action=cast.sampleOnce(skill.preparationTicks()==0?cast.length-.4+age:age);
                for(String bone:new String[]{"head","body","right_arm"})base.overlayChannels(action,bone,Pose.ALL,0,0);
            }
            return base;
        }
        boolean rising = skillPlayer != null && verticalSpeed(skillPlayer) > 0;
        double preparation = skill.preparationTicks() / 20.0;
        String name = switch (skill.skill()) {
            case 1 -> firstPerson ? (age < preparation ? "fa_fp_attack_falling" : "fa_fp_hitfalling")
                    : (age < preparation ? "fa_attack_falling" : "fa_hit_falling");
            case 2 -> firstPerson ? "fa_fp_hitcombo2" : "fa_hit_combo2";
            case 3 -> firstPerson ? "fa_fp_skill3" : "fa_skill3";
            case 4 -> firstPerson ? (rising ? "fa_fp_jump" : "fa_fp_fall")
                    : (rising ? "fa_jump" : "fa_fall");
            default -> "";
        };
        Clip clip = CLIPS.get(name);
        if (clip == null) return base;
        double time = age;
        if (skill.skill() == 1) {
            time = age < preparation ? age * clip.length / preparation : age - preparation;
        }
        Pose action = clip.sampleOnce(time);
        if (skill.skill() == 4) {
            Clip idle = CLIPS.get(firstPerson ? "fa_fp_idle" : "fa_idle");
            Pose rest = idle == null ? base : idle.sample(0);
            for (String bone : new String[]{"body","right_arm","left_arm"}) {
                action.rotation.put(bone,rest.rotation.getOrDefault(bone,ZERO));
            }
            return action;
        }
        if (skill.skill() == 3) return action;
        if (skill.skill() == 2) base.overlayArm(action,"left_arm","left_item");
        else {
            base.overlayArm(action,"left_arm","left_item");
            base.overlayArm(action,"right_arm","right_item");
        }
        return base;
    }

    static void skillLanded(AbstractClientPlayer player) {
        AIR_MOTION.remove(player);
        LANDINGS.put(player,player.level().getGameTime());
        LANDING_WALK.put(player,new LandingMovement(player.getDeltaMovement().horizontalDistance()>.02,
                player.isSprinting(),player.level().getGameTime()));
        TOUCHDOWNS.put(player,player.level().getGameTime());
    }

    static void cancelHandGesture(AbstractClientPlayer player) {
        HandGesture gesture = HAND_GESTURES.get(player);
        if (gesture != null) gesture.stop(player.level().getGameTime());
    }

    static void resetHandBlend(AbstractClientPlayer player) {
        HAND_BLEND.remove(player);
    }

    private static Pose sampleThirdPersonAttack(Attack attack, double renderTick) {
        if (attack == null) return null;
        Clip clip = CLIPS.get("fa_hit_combo" + attack.combo);
        if (clip == null) return null;
        double time = (renderTick - attack.start) / 20;
        return time >= 0 && time < clip.length ? clip.sampleOnce(time) : null;
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
            LANDING_INTERRUPTED.remove(playerId);
            FREE_ARM_BLENDS.remove(player);
            LEG_CYCLES.remove(player);
            AIR_MOTION.remove(player);
            LANDINGS.remove(player);
            LANDING_WALK.remove(player);
            TOUCHDOWNS.remove(player);
            LANDING_ARMS.remove(player);
            ENTRY_LANDINGS.remove(player);
            GROUND_Y.remove(player);
            return null;
        }
        long now = player.level().getGameTime();
        double renderTick = now + (double)partialTick;
        String phase = state.phase();
        double ageTicks = renderTick - state.phaseStart();
        double age = ageTicks / 20;
        boolean awakening = phase.equals(StyleService.AWAKENING);
        boolean entry = awakening || phase.equals("DRAWING") || phase.equals("UPGRADING");
        boolean simpleEntry = phase.equals(StyleService.SIMPLE_DRAWING);
        boolean simpleSheath = phase.equals("SHEATHING") && state.mode() == StyleMode.SIMPLE;
        boolean entering = entry && ageTicks < StyleService.DRAW_TICKS;
        float comboTime = (now - state.comboStart() + partialTick) / 20f;
        String combo = phase.equals("ACTIVE") ? activeComboClip(state, comboTime, "fa_hit_combo") : null;
        double speed = Math.hypot(player.getX() - player.xo, player.getZ() - player.zo);
        boolean moving = speed > .001;
        boolean flying = player.getAbilities().flying;
        AirMotion air = updateAirMotion(player, now);
        boolean airborne = air!=null && !flying;
        boolean fallingAttack = phase.equals("ACTIVE") && state.fallingAttackStart() >= 0;
        if (fallingAttack) combo = null;
        interruptAttacksOnLanding(player, state, renderTick, air);
        if (new Attack(state.combo(), state.comboStart()).equals(LANDING_INTERRUPTED.get(playerId))) combo = null;
        LegCycle cycle = LEG_CYCLES.computeIfAbsent(player, ignored -> new LegCycle());
        String crouchIdle = wrapCycle(cycle.phase) >= .25 && wrapCycle(cycle.phase) < .75 ? "fa_idle_crouch2" : "fa_idle_crouch";
        double idlePhase = wrapCycle(cycle.phase);
        String standingIdle = idlePhase >= .17 && idlePhase < .55 ? "fa_idle2" : "fa_idle";
        String clip = switch (phase) {
            case StyleService.AWAKENING -> ageTicks < StyleService.AWAKEN_ANIMATION_TICKS ? "fa_enter_mode_full" : "fa_idle";
            case "UPGRADING" -> entering ? "fa_enter_mode_common2" : "fa_idle";
            case StyleService.SIMPLE_DRAWING -> "fa_enter_mode_simple";
            case "DRAWING" -> entering ? "fa_enter_mode_common" : "fa_idle";
            case "SHEATHING" -> simpleSheath ? "fa_exit_mode_simple" : "fa_exit_mode";
            case "ACTIVE" -> combo != null
                    ? combo
                    : player.isCrouching() ? (moving ? "fa_walk_crouch" : crouchIdle) : moving ? (player.isSprinting() ? "fa_run" : walkingBackwards(player) ? "fa_walk_back" : "fa_walk") : standingIdle;
            default -> player.isCrouching() ? (moving ? "fa_walk_crouch" : crouchIdle) : null;
        };
        double t = combo == null ? age : comboTime;
        if (phase.equals("UPGRADING") && entering) t = StyleService.upgradeAnimationSeconds(ageTicks);
        boolean action = entry || simpleEntry || phase.equals("SHEATHING") || combo != null;
        if (clip == null && flying) clip = "fa_idle";
        if (!action && flying) t = renderTick / 20;
        if ("fa_idle".equals(clip) || "fa_idle2".equals(clip)) {
            if (entry) t = (ageTicks - (awakening ? StyleService.AWAKEN_ANIMATION_TICKS : StyleService.DRAW_TICKS)) / 20;
            else if (phase.equals("ACTIVE")) t = (ageTicks + StyleService.ENTRY_TICKS - StyleService.DRAW_TICKS) / 20;
        }
        boolean once = simpleEntry || simpleSheath || awakening || (phase.equals("UPGRADING") && entering);
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
        if (!entry && !simpleEntry && !simpleSheath) {
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
        boolean baseLanding = air == null && landingAge >= 0 && landingClip != null && landingAge < landingClip.length;
        if (baseLanding && landingMovement != null) landingMovement.update(speed > .02, now);
        Pose heldPose = LANDING_ARMS.get(player);
        TimedClip fallingOverlay = phase.equals("ACTIVE") ? fallingAction(state, renderTick, fallingAttack) : null;
        boolean waitingForJump = fallingOverlay == null && landingPlaying && landingMovement != null && landingMovement.waitForJump
                && renderTick - landing < 2 && heldPose != null;
        Pose pose = once ? selected.sampleOnce(t) : selected.sample(t);
        if(clip.equals("fa_jump"))sampleJumpRotations(pose,selected,t);
        // A crouched attack keeps the crouch's torso bend and offset while the
        // attack supplies its sideways twist. Both clips remain independently timed.
        if (combo != null && player.isCrouching() && !airborne) {
            String crouchClipName = moving ? "fa_walk_crouch" : crouchIdle;
            Clip crouchClip = CLIPS.get(crouchClipName);
            if (crouchClip != null) {
                double crouchTime = moving ? wrapCycle(cycle.phase) * crouchClip.length : age;
                // The attack has priority on Y/Z rotation; crouch owns X and translation.
                pose.overlayChannels(crouchClip.sample(crouchTime), "body", Pose.X, Pose.ALL, Pose.ALL);
            }
        }
        String landingTorso = null;
        if (baseLanding && ("fa_hit_combo1".equals(combo) || "fa_hit_combo2".equals(combo))) {
            landingTorso = landingMovement != null && landingMovement.walking
                    ? "fa_fall_ground_walk_transition" : "fa_fall_ground";
            Clip torsoClip = CLIPS.get(landingTorso);
            if (torsoClip != null) {
                Pose landingPose = torsoClip.sampleOnce(landingAge);
                if ("fa_fall_ground_walk_transition".equals(landingTorso) && moving && landingAge >= 1.25) {
                    Clip gait = CLIPS.get(player.isSprinting() ? "fa_run" : "fa_walk");
                    if (gait != null) {
                        Pose gaitPose = gait.sample(wrapCycle(cycle.phase) * gait.length);
                        float progress = (float)Math.clamp((landingAge - 1.25) / Math.max(.001, torsoClip.length - 1.25), 0, 1);
                        float eased = progress * progress * (3 - 2 * progress);
                        float[] from = landingPose.position.getOrDefault("body", ZERO);
                        float[] to = gaitPose.position.getOrDefault("body", ZERO);
                        float[] position = from.clone();
                        position[1] = Mth.lerp(eased, from[1], to[1]);
                        landingPose.position.put("body", position);
                    }
                }
                pose.overlayChannels(landingPose, "body", Pose.X, Pose.ALL, Pose.ALL);
            }
        }
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
                if ("fa_fall_ground_walk_transition".equals(clip)) {
                    float progress = (float)Math.clamp((landingAge - 1.25) / Math.max(.001, selected.length - 1.25), 0, 1);
                    float eased = progress * progress * (3 - 2 * progress);
                    float[] landingPosition = pose.position.getOrDefault("body", ZERO);
                    float[] gaitPosition = gaitPose.position.getOrDefault("body", ZERO);
                    float[] bodyPosition = landingPosition.clone();
                    bodyPosition[1] = Mth.lerp(eased, landingPosition[1], gaitPosition[1]);
                    pose.position.put("body", bodyPosition);
                }
            }
        }
        if (fallingOverlay != null) {
            Clip overlayClip = CLIPS.get(fallingOverlay.name);
            if (overlayClip != null) {
                Pose overlay = fallingOverlay.once ? overlayClip.sampleOnce(fallingOverlay.time) : overlayClip.sample(fallingOverlay.time);
                overlayFallingArms(pose, overlay);
            }
        }
        if(phase.equals("ACTIVE")){pose.rotation.remove("head");pose.position.remove("head");}
        if (waitingForJump) pose = heldPose;
        // Blend clip changes briefly. Filtering every frame delays authored gestures
        // and softens stepped keyframes by a different amount at each frame rate.
        var skill = SkillHud.STATUS.get(playerId);
        pose = skillPose(pose, skill, renderTick, false);
        Playback previous = LAST.get(playerId);
        String source = waitingForJump ? "landing_wait" : clip + "|" + (legPose == null ? "" : cycle.gait)
                + (combo != null && player.isCrouching() && !airborne ? "|crouch:" + (moving ? "walk" : crouchIdle) : "")
                + (landingTorso == null ? "" : "|torso:" + landingTorso)
                + (fallingOverlay == null ? "" : "|arms:" + fallingOverlay.name + ":" + state.fallingHitStart());
        String visionName=visionClip(skill,player,renderTick);
        if(visionName!=null)source="vision:"+visionName;
        if (skill != null && skill.skill() != 0 && skill.skill()!=6) source += "|skill:" + skill.skill() + ":" + skill.start() + ":" + (renderTick-skill.start()<skill.preparationTicks());
        Pose transitionFrom = null;
        double transitionStart = renderTick;
        if (previous != null
                && renderTick >= previous.renderTick && renderTick - previous.renderTick < 20) {
            if (!source.equals(previous.source)) {
                transitionFrom = previous.pose;
            } else if (renderTick - previous.transitionStart < TRANSITION_TICKS) {
                transitionFrom = previous.transitionFrom;
                transitionStart = previous.transitionStart;
            }
        }
        if (transitionFrom != null) {
            float progress = (float)Math.clamp((renderTick - transitionStart) / TRANSITION_TICKS, 0, 1);
            pose = transitionFrom.mix(pose, progress * progress * (3 - 2 * progress));
        }
        Pose landingArms = LANDING_ARMS.get(player);
        if (fallingOverlay == null && landingPlaying && !waitingForJump && landingArms != null && landingAge < .2) {
            float progress = (float)Math.clamp(landingAge / .2, 0, 1);
            progress = progress * progress * (3 - 2 * progress);
            Pose arms = landingArms.mix(pose, progress);
            pose.overlayArm(arms, "right_arm", "right_item");
            pose.overlayArm(arms, "left_arm", "left_item");
        }
        FreeArmBlends armBlends = FREE_ARM_BLENDS.computeIfAbsent(player, ignored -> new FreeArmBlends());
        if ((skill == null || skill.skill() == 0) && ("fa_hit_combo1".equals(combo) || "fa_hit_combo2".equals(combo))) {
            boolean leftFree = "fa_hit_combo1".equals(combo);
            ArmAttacks attacks = ATTACKS.get(playerId);
            Attack continuing = attacks == null ? null : leftFree ? attacks.left : attacks.right;
            Pose freePose = sampleThirdPersonAttack(continuing, renderTick);
            String freeSource = freePose == null ? null : "attack:" + continuing.combo + ":" + continuing.start;
            if (freePose == null) {
                String baseName = player.isCrouching() ? (moving ? "fa_walk_crouch" : crouchIdle)
                        : moving ? (player.isSprinting() ? "fa_run" : walkingBackwards(player) ? "fa_walk_back" : "fa_walk") : standingIdle;
                Clip locomotion = CLIPS.get(baseName);
                double baseTime = moving && locomotion != null ? wrapCycle(cycle.phase) * locomotion.length
                        : player.isCrouching() ? age : (ageTicks + StyleService.ENTRY_TICKS - StyleService.DRAW_TICKS) / 20;
                boolean baseOnce = false;
                if (air != null) {
                    TimedClip baseAir = air.animation("fa_", now, partialTick);
                    baseName = baseAir.name;
                    baseTime = baseAir.time;
                    baseOnce = baseAir.once;
                } else if (baseLanding) {
                    baseName = landingMovement != null && landingMovement.walking
                            ? "fa_fall_ground_walk_transition" : "fa_fall_ground";
                    baseTime = landingAge;
                    baseOnce = true;
                }
                Clip baseClip = CLIPS.get(baseName);
                if (baseClip != null) freePose = baseOnce ? baseClip.sampleOnce(baseTime) : baseClip.sample(baseTime);
                freeSource = baseName;
                if (baseLanding && landingMovement != null && landingMovement.waitForJump
                        && renderTick - landing < 2 && heldPose != null) {
                    freePose = heldPose;
                    freeSource = "landing_wait";
                } else if (baseLanding && freePose != null && heldPose != null && landingAge < .2) {
                    float progress = (float)Math.clamp(landingAge / .2, 0, 1);
                    freePose = heldPose.mix(freePose, progress * progress * (3 - 2 * progress));
                }
            }
            if (freePose != null) {
                String arm = leftFree ? "left_arm" : "right_arm";
                String item = leftFree ? "left_item" : "right_item";
                pose.overlayArm(freePose, arm, item);
                (leftFree ? armBlends.left : armBlends.right).apply(pose, new ArmSource(freeSource, 0), arm, item, renderTick);
            }
            (leftFree ? armBlends.right : armBlends.left).capture(pose, leftFree ? "right_arm" : "left_arm", leftFree ? "right_item" : "left_item", renderTick);
        } else {
            armBlends.left.capture(pose, "left_arm", "left_item", renderTick);
            armBlends.right.capture(pose, "right_arm", "right_item", renderTick);
        }
        if (entry && airborne && state.entryAirborne() && !ENTRY_LANDINGS.containsKey(player)) {
            Clip fall = CLIPS.get("fa_fall");
            Clip fallLoop = CLIPS.get("fa_fall_loop");
            Pose fallingLegs = fall != null && age < fall.length ? fall.sampleOnce(age)
                    : fallLoop != null ? fallLoop.sample(Math.max(0, age - (fall == null ? 0 : fall.length))) : null;
            if (fallingLegs != null) {
                for (String bone : new String[]{"right_leg", "left_leg"}) {
                    pose.rotation.remove(bone); pose.position.remove(bone); pose.scale.remove(bone);
                    pose.overlayChannels(fallingLegs, bone, Pose.ALL, Pose.ALL, Pose.ALL);
                }
            }
        }
        EntryLanding entryLanding = ENTRY_LANDINGS.get(player);
        if (entry && entryLanding != null && entryLanding.phaseStart == state.phaseStart()) {
            Clip ground = CLIPS.get("fa_fall_ground");
            double elapsed = Math.max(0, (renderTick - entryLanding.tick) / 20);
            if (ground != null && elapsed < ground.length + .2) {
                Pose groundPose = ground.sampleOnce(elapsed);
                float in = (float)Math.clamp(elapsed / .2, 0, 1);
                float out = (float)Math.clamp((elapsed - ground.length) / .2, 0, 1);
                in = in * in * (3 - 2 * in);
                out = out * out * (3 - 2 * out);
                Pose start = entryLanding.pose == null ? pose : entryLanding.pose;
                Pose landingPose = start.mix(groundPose, in).mix(pose, out);
                for (String bone : new String[]{"right_leg", "left_leg"})
                    pose.overlayChannels(landingPose, bone, Pose.ALL, Pose.ALL, Pose.ALL);
                pose.overlayChannels(landingPose, "body", 0, Pose.Y, 0);
            }
        }
        boolean locomotion=!action && fallingOverlay==null && visionName==null && (skill==null || skill.skill()==0 || skill.skill()==4);
        smoothJumpRotation(JUMP_ROTATION,player,pose,locomotion,airborne,renderTick,2*TRANSITION_TICKS);
        // SwordLayer reads the same defense bones through the render pose cache.
        pose = defensePose(player, state, pose, renderTick, false);
        LAST.put(playerId, new Playback(clip, source, pose, renderTick, transitionFrom, transitionStart));
        return pose;
    }

    private static double wrapCycle(double phase) {
        return phase - Math.floor(phase);
    }

    private static void interruptAttacksOnLanding(AbstractClientPlayer player, StyleState state, double tick, AirMotion air) {
        Long landing = LANDINGS.get(player);
        Clip landingClip = CLIPS.get("fa_fall_ground");
        if (air != null || landing == null || landingClip == null || tick >= landing + 2 + landingClip.length * 20
                || !state.phase().equals("ACTIVE") || (state.combo() != 1 && state.combo() != 2)
                || state.comboStart() >= landing + 2) return;
        UUID id = player.getUUID();
        LANDING_INTERRUPTED.put(id, new Attack(state.combo(), state.comboStart()));
        ArmAttacks attacks = ATTACKS.get(id);
        if (attacks != null) {
            Attack right = attacks.right != null && attacks.right.combo == 3 ? attacks.right : null;
            Attack left = attacks.left != null && attacks.left.combo == 3 ? attacks.left : null;
            if (right == null && left == null) ATTACKS.remove(id);
            else ATTACKS.put(id, new ArmAttacks(right, left));
        }
    }

    private static void sampleJumpRotations(Pose pose,Clip jump,double time){
        Pose slower=jump.sampleOnce(time*.5);
        pose.overlayChannels(slower,"left_arm",Pose.ALL,0,0);
        pose.overlayChannels(slower,"right_arm",Pose.ALL,0,0);
    }

    private static void smoothJumpRotation(Map<AbstractClientPlayer,JumpRotation> animations,AbstractClientPlayer player,
                                           Pose pose,boolean locomotion,boolean air,double tick,double duration){
        if(!locomotion){animations.remove(player);return;}
        animations.computeIfAbsent(player,ignored->new JumpRotation()).apply(pose,air,tick,duration);
    }

    private static final class JumpRotation {
        private float[] right,left,fromRight,fromLeft;
        private boolean wasAirborne;
        private double transitionTick,lastTick;

        private void apply(Pose pose,boolean airborne,double tick,double duration){
            if(right==null || tick<lastTick || tick-lastTick>duration*2){
                fromRight=fromLeft=null;
            }else if(airborne!=wasAirborne){
                fromRight=right;
                fromLeft=left;
                transitionTick=tick;
            }
            right=pose.rotation.getOrDefault("right_arm",ZERO);
            left=pose.rotation.getOrDefault("left_arm",ZERO);
            double progress=(tick-transitionTick)/duration;
            if(fromRight!=null && progress<1){
                float blend=(float)AttackHotbar.ease(progress);
                right=Pose.blendAngles(fromRight,right,blend);
                left=Pose.blendAngles(fromLeft,left,blend);
                pose.rotation.put("right_arm",right);
                pose.rotation.put("left_arm",left);
            }else fromRight=fromLeft=null;
            wasAirborne=airborne;
            lastTick=tick;
        }
    }

    private static final class LegCycle {
        private String gait;
        private double phase;
        private double previousTick = Double.NaN;
        private double takeoffTick = Double.NaN;
        private double takeoffPhase;
        private double targetPhase;
        private double nextLead=Double.NaN;

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
            if(gait==null)gait=movingGait;
            if (Double.isNaN(takeoffTick)) {
                takeoffTick = tick;
                takeoffPhase = wrapCycle(phase);
                // 1 and 0 are the same pose, so a phase past .75 targets 1.
                targetPhase=Double.isNaN(nextLead)?Math.round(takeoffPhase*2)/2.0:nextLead;
                if(targetPhase-takeoffPhase>.5)targetPhase--;
                if(takeoffPhase-targetPhase>.5)targetPhase++;
                nextLead=Double.NaN;
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

    static void remoteJump(fightingarts.StyleNetwork.AirJump event){
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(mc.level==null || !(mc.level.getPlayerByUUID(event.player()) instanceof AbstractClientPlayer player)
                || player==mc.player)return;
        var legs=LEG_CYCLES.computeIfAbsent(player,ignored->new LegCycle());
        legs.takeoffTick=Double.NaN;
        legs.nextLead=event.rightLeading()?.5:0;
        long now=mc.level.getGameTime();
        var air=new AirMotion(Math.min(event.tick(),now),event.velocity(),player.getGravity(),player.getY());
        air.remoteGrace=now+3;
        AIR_MOTION.put(player,air);
        TOUCHDOWNS.remove(player);
    }

    private static double verticalSpeed(Player player){
        if(player instanceof net.minecraft.client.player.RemotePlayer)return player.getY()-player.yo;
        return player.getDeltaMovement().y;
    }

    private static AirMotion updateAirMotion(AbstractClientPlayer player, long now) {
        AirMotion currentAir=AIR_MOTION.get(player);
        boolean startingRemoteJump=currentAir!=null && currentAir.remoteGrace>=now;
        if ((player.onGround() && !startingRemoteJump) || player.getAbilities().flying) {
            AirMotion previous = AIR_MOTION.remove(player);
            if (previous != null && previous.fallStart >= 0 && !player.getAbilities().flying) TOUCHDOWNS.put(player, now);
            if (previous != null && previous.fallStart >= 0 && !player.getAbilities().flying
                    && previous.highestY - player.getY() >= 2.0 - 1e-3) {
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
        StyleState current = STATES.get(player.getUUID());
        if (current == null || !current.phase().equals("DRAWING") || current.entryAirborne()) ENTRY_LANDINGS.remove(player);
        LANDING_WALK.remove(player);
        LANDING_ARMS.remove(player);
        Long touchdown = TOUCHDOWNS.remove(player);
        if (touchdown != null && now - touchdown <= 2) {
            LegCycle legs = LEG_CYCLES.get(player);
            if (legs != null) legs.phase = Math.floor(wrapCycle(legs.phase) * 2 + .001) / 2 + .5;
        }
        double velocity = verticalSpeed(player);
        AirMotion air = AIR_MOTION.computeIfAbsent(player, ignored -> new AirMotion(now, velocity, player.getGravity(),
                Math.max(player.getY(), GROUND_Y.getOrDefault(player, player.getY()))));
        air.highestY = Math.max(air.highestY, player.getY());
        air.horizontalSpeed = Math.hypot(player.getX() - player.xo, player.getZ() - player.zo);
        air.sprinting = player.isSprinting();
        if (velocity <= 0 && air.fallStart < 0 && !startingRemoteJump) air.fallStart = now;
        return air;
    }

    public static void tickMotion(AbstractClientPlayer player) {
        StyleState state = STATES.get(player.getUUID());
        if (state != null && (state.phase().equals("ACTIVE") || state.phase().equals("DRAWING"))) updateAirMotion(player, player.level().getGameTime());
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
    private record EntryLanding(long tick, long phaseStart, Pose pose) {}
    private static void overlayFallingArms(Pose target, Pose overlay) {
        target.overlayArm(overlay, "right_arm", "right_item");
        target.overlayArm(overlay, "left_arm", "left_item");
    }

    private static TimedClip fallingAction(StyleState state, double renderTick, boolean fallingAttack) {
        if (state.fallingHitStart() >= 0) {
            String hitName = "fa_hit_falling";
            Clip hit = CLIPS.get(hitName);
            if (hit == null) return null;
            double hitAge = (renderTick - state.fallingHitStart()) / 20;
            return hitAge >= 0 && hitAge < hit.length ? new TimedClip(hitName, hitAge, true) : null;
        }
        return fallingAttack ? fallingAttackAnimation("fa_", renderTick - state.fallingAttackStart()) : null;
    }
    private static TimedClip fallingAttackAnimation(String prefix, double elapsedTicks) {
        String name = prefix + "attack_falling";
        Clip intro = CLIPS.get(name);
        double age = Math.max(0, elapsedTicks / 20);
        if (intro != null && age < intro.length) return new TimedClip(name, age, true);
        return new TimedClip(name + "_loop", age - (intro == null ? 0 : intro.length), false);
    }
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

    private record PlayingStrike(Attack attack, double started) {
        private String clipName() {
            return attack.combo == 4 ? "fa_fp_hitfalling" : "fa_fp_hitcombo" + attack.combo;
        }

        private boolean finished(double now, long interruption) {
            double age = now - started;
            Clip clip = CLIPS.get(clipName());
            double limit = switch (attack.combo) {
                case 3 -> 1.92;
                case 4 -> FIRST_PERSON_FALLING_HIT_END_SECONDS;
                default -> 1.13;
            };
            return clip == null || age >= Math.min(clip.length, limit) * 20
                    || interruption > attack.start && age >= FirstPersonStrikes.MIN_PRESENTATION_TICKS;
        }
    }

    private static final class FirstPersonStrikes {
        private static final int MIN_PRESENTATION_TICKS = 6;
        private final ArrayDeque<Attack> pending = new ArrayDeque<>();
        private double frameTick = Double.NaN;
        private double time;
        private double nextStart;
        private PlayingStrike right, left;

        private void add(Attack strike) {
            // A leap hit replaces the provisional combo from the same server tick.
            if (strike.combo == 4 && pending.peekLast() != null && pending.peekLast().start == strike.start) {
                pending.removeLast();
            }
            pending.addLast(strike);
        }

        private void advance(double tick, long interruption) {
            // Measure from frames the player can actually see, even after a delayed snapshot or a frame stall.
            if (!Double.isNaN(frameTick)) time += Math.clamp(tick - frameTick, 0, 3);
            frameTick = tick;
            if (right != null && right.finished(time, interruption)) right = null;
            if (left != null && left.finished(time, interruption)) left = null;
            if (pending.isEmpty() || time < nextStart) return;
            Attack attack = pending.removeFirst();
            PlayingStrike strike = new PlayingStrike(attack, time);
            if (strike.finished(time, interruption)) return;
            if (attack.combo != 2) right = strike;
            if (attack.combo != 1) left = strike;
            nextStart = time + MIN_PRESENTATION_TICKS;
        }

        private PlayingStrike playing(boolean leftHand) {
            return leftHand ? left : right;
        }
    }

    private static final class FreeArmBlends {
        private final HandBlend right = new HandBlend();
        private final HandBlend left = new HandBlend();
    }

    private static final class FirstPersonBlend {
        private final HandBlend right = new HandBlend();
        private final HandBlend left = new HandBlend();
        private final FirstPersonClock clock = new FirstPersonClock();
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
        private double duration = FIRST_PERSON_TRANSITION_TICKS;
        private double lastTick;

        private void capture(Pose pose, String arm, String item, double tick) {
            previous = new Pose(new HashMap<>(), new HashMap<>(), new HashMap<>());
            previous.overlayArm(pose, arm, item);
            source = null;
            from = null;
            lastTick = tick;
        }

        private void apply(Pose pose, ArmSource target, String arm, String item, double tick) {
            if (previous == null || tick < lastTick || tick - lastTick > FIRST_PERSON_TRANSITION_TICKS) {
                from = null;
            } else if (!target.equals(source)) {
                // A new attack on this hand starts at its authored pose, even if its previous attack is unfinished.
                from = target.clip.startsWith("fa_fp_hitcombo") || target.clip.equals("fa_fp_hitfalling") ? null : previous;
                started = tick;
                duration = target.clip.equals("fa_fp_attack_falling")
                        ? FIRST_PERSON_FALLING_ENTRY_TICKS : FIRST_PERSON_TRANSITION_TICKS;
            }
            Pose hand = new Pose(new HashMap<>(), new HashMap<>(), new HashMap<>());
            hand.overlayArm(pose, arm, item);
            double progress = (tick - started) / duration;
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
        private long remoteGrace = -1;

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

    private static void followHeadPitch(net.minecraft.client.model.geom.ModelPart arm, float pitch) {
        if (pitch == 0) return;
        // Prepend the pitch in player/model space, so a crossed arm keeps its own yaw and roll.
        var rotation = new org.joml.Matrix4f().rotationX(pitch).rotateZYX(arm.zRot, arm.yRot, arm.xRot);
        var angles = rotation.getEulerAnglesZYX(new org.joml.Vector3f());
        arm.setRotation(angles.x, angles.y, angles.z);
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

    private record Playback(String clip, String source, Pose pose, double renderTick, Pose transitionFrom, double transitionStart) {}
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
        static final int X = 1, Y = 2, Z = 4, ALL = X | Y | Z;

        void overlayChannels(Pose source, String bone, int rotationAxes, int positionAxes, int scaleAxes) {
            overlayAxes(rotation, source.rotation, bone, rotationAxes, ZERO);
            overlayAxes(position, source.position, bone, positionAxes, ZERO);
            overlayAxes(scale, source.scale, bone, scaleAxes, ONE);
        }

        private static void overlayAxes(Map<String,float[]> target, Map<String,float[]> source,
                                        String bone, int axes, float[] defaultValue) {
            float[] incoming = source.get(bone);
            if (incoming == null || axes == 0) return;
            if (axes == ALL) {
                target.put(bone, incoming);
                return;
            }
            float[] current = target.getOrDefault(bone, defaultValue);
            float[] combined = current.clone();
            for (int axis = 0; axis < 3; axis++) if ((axes & (1 << axis)) != 0) combined[axis] = incoming[axis];
            target.put(bone, combined);
        }

        void overlayArm(Pose source, String arm, String item) {
            for (String bone : new String[]{arm, item, item + "2"}) {
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
