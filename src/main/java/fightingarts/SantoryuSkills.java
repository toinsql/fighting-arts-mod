package fightingarts;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.Consumer;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.*;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.network.*;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Server-owned skill costs, clocks, movement and damage. */
public final class SantoryuSkills {
    public static final int[] COST = {25,15,75,20,1,1,125};
    public static final int[] COOLDOWN = {200,300,800,60,400,600,1800};
    public static final String[] NAMES = {"Investida das Três Presas","Fenda da Lua Sombria",
            "Vendaval do Demônio","Salto da Garça Rubra","Passo da Tempestade","Olho da Fenda","Dragão das Três Marés"};
    public static final int MAX_ENERGY = 200;
    public static final int AWAKENING_REQUIRED_HITS = 65;
    public static final int SIMPLE_CHARGES = 5;
    public static final int PASSIVE_INTERVAL = 10;
    public static final int NORMAL_HIT_ENERGY = 2;
    public static final int SKILL_HIT_ENERGY = 16;
    public static final int MONSTER_KILL_ENERGY = 12;
    public static final int SIGHT_RANGE = 30;
    public static final int SIGHT_THROUGH_WALLS = SIGHT_RANGE - 5;
    public static final int SIGHT_WHITE = 0xffffff;
    public static final int SIGHT_YELLOW = 0xffd23e;
    public static final int SIGHT_RED = 0xff353d;

    public static final int DRAGON_AIM_TICKS = 30;
    private static final int DRAGON_REACH = 30;
    private static final double DRAGON_HEAD_WIDTH = .85;
    private static final double DRAGON_HEAD_HEIGHT = .60;
    private static final ResourceLocation SIGHT_SLOW = ResourceLocation.fromNamespaceAndPath("fightingarts","sight_slow");
    public static final int DASH_PREPARATION_TICKS = 15;
    public static final double SPIN_START = 0.66667;
    public static final double SPIN_END = 2.125;
    private static final String ENERGY = "fightingarts_energy";
    private static final String AWAKENING_PERCENT = "fightingarts_awakening";
    private static final String AWAKENING_HITS = "fightingarts_awakening_hits";
    private static final String GUARD_CHARGES = "fightingarts_guard_charges";
    private static final Map<UUID, Combat> PLAYERS = new HashMap<>();
    private static final Map<LivingEntity, Stun> STUNS = new java.util.concurrent.ConcurrentHashMap<>();
    public static Consumer<Status> clientStatus = status -> {};
    public static Consumer<Effect> clientEffect = effect -> {};
    private SantoryuSkills() {}

    public record Status(UUID player, int energy, int awakening, int skill, long start,
                         long ready1, long ready2, long ready3, long ready4, long ready5, long ready6, long ready7, long visionStart, long visionEnd, int visionExitTicks, boolean leapFlight, int preparationTicks,
                         long guardReady1, long guardReady2, long guardReady3, long guardReady4, long guardReady5) implements CustomPacketPayload {
        public static final Type<Status> TYPE = SantoryuSkills.type("skill_status");
        static final StreamCodec<RegistryFriendlyByteBuf,Status> CODEC = StreamCodec.of((b,s)->{
            b.writeUUID(s.player); b.writeVarInt(s.energy); b.writeVarInt(s.awakening);
            b.writeVarInt(s.skill); b.writeLong(s.start);
            b.writeLong(s.ready1); b.writeLong(s.ready2); b.writeLong(s.ready3); b.writeLong(s.ready4); b.writeLong(s.ready5); b.writeLong(s.ready6); b.writeLong(s.ready7); b.writeLong(s.visionStart); b.writeLong(s.visionEnd); b.writeVarInt(s.visionExitTicks); b.writeBoolean(s.leapFlight); b.writeVarInt(s.preparationTicks);
            b.writeLong(s.guardReady1); b.writeLong(s.guardReady2); b.writeLong(s.guardReady3);
            b.writeLong(s.guardReady4); b.writeLong(s.guardReady5);
        },b->new Status(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readLong(),
                b.readLong(),b.readLong(),b.readLong(),b.readLong(),b.readLong(),b.readLong(),b.readLong(),b.readLong(),b.readLong(),b.readVarInt(),b.readBoolean(),b.readVarInt(),b.readLong(),b.readLong(),b.readLong(),b.readLong(),b.readLong()));
        public Type<Status> type(){return TYPE;}
        public long ready(int index) {
            return switch(index){case 0->ready1;case 1->ready2;case 2->ready3;case 3->ready4;case 4->ready5;case 5->ready6;default->ready7;};
        }
        public long guardReady(int index) {
            return switch (index) {
                case 0 -> guardReady1; case 1 -> guardReady2; case 2 -> guardReady3;
                case 3 -> guardReady4; case 4 -> guardReady5;
                default -> throw new IndexOutOfBoundsException(index);
            };
        }
        public boolean hasSimpleCharge(double now) {
            for (int i = 0; i < SIMPLE_CHARGES; i++) {
                if (guardReady(i) <= now) return true;
            }
            return false;
        }
    }
    public record SightTarget(int entity,float rawDamage,float damageAfterDefense,int tint) implements CustomPacketPayload{
        static final Type<SightTarget> TYPE=SantoryuSkills.type("sight_target");
        static final StreamCodec<RegistryFriendlyByteBuf,SightTarget> CODEC=StreamCodec.of(
                (b,s)->{b.writeVarInt(s.entity);b.writeFloat(s.rawDamage);b.writeFloat(s.damageAfterDefense);b.writeInt(s.tint);},
                b->new SightTarget(b.readVarInt(),b.readFloat(),b.readFloat(),b.readInt()));
        public Type<SightTarget> type(){return TYPE;}
    }
    public static Consumer<SightTarget> clientSight=t->{};
    public static java.util.function.Predicate<Player> clientRushing=player->false;

    public static java.util.function.Predicate<Player> clientLeapFlight=player->false;

    private static boolean leapFlight(Combat state){
        return state.leapStart>=0 && state.leapLanded<0;
    }

    public static boolean leapFlight(Player player){
        return player instanceof ServerPlayer server?leapFlight(combat(server)):clientLeapFlight.test(player);
    }

    public static boolean rushing(Player player){
        return player instanceof ServerPlayer server?combat(server).skill==5:clientRushing.test(player);
    }
    public record Request(int skill, float distance) implements CustomPacketPayload {
        static final Type<Request> TYPE=SantoryuSkills.type("use_skill");
        static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of(
                (b,r)->{b.writeVarInt(r.skill);b.writeFloat(r.distance);},
                b->new Request(b.readVarInt(),b.readFloat()));
        public Type<Request> type(){return TYPE;}
    }
    public record Effect(int kind, Vec3 from, Vec3 to, int target) implements CustomPacketPayload {
        static final Type<Effect> TYPE=SantoryuSkills.type("skill_effect");
        static final StreamCodec<RegistryFriendlyByteBuf,Effect> CODEC=StreamCodec.of((b,e)->{
            b.writeVarInt(e.kind); writeVec(b,e.from); writeVec(b,e.to); b.writeInt(e.target);
        },b->new Effect(b.readVarInt(),readVec(b),readVec(b),b.readInt()));
        public Type<Effect> type(){return TYPE;}
    }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String id) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,id));
    }
    private static void writeVec(RegistryFriendlyByteBuf b,Vec3 v){b.writeDouble(v.x);b.writeDouble(v.y);b.writeDouble(v.z);}
    private static Vec3 readVec(RegistryFriendlyByteBuf b){return new Vec3(b.readDouble(),b.readDouble(),b.readDouble());}
    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event)->{
            var r=event.registrar("9");
            r.playToServer(Request.TYPE,Request.CODEC,(p,c)->c.enqueueWork(()->{
                if(c.player() instanceof ServerPlayer player) use(player,p.skill,p.distance);
            }));
            r.playToClient(SightTarget.TYPE,SightTarget.CODEC,(p,c)->c.enqueueWork(()->clientSight.accept(p)));
            r.playToClient(Status.TYPE,Status.CODEC,(p,c)->c.enqueueWork(()->clientStatus.accept(p)));
            r.playToClient(Effect.TYPE,Effect.CODEC,(p,c)->c.enqueueWork(()->clientEffect.accept(p)));
        });
    }
    public static void request(int skill,float distance){PacketDistributor.sendToServer(new Request(skill,distance));}
    private static Combat combat(ServerPlayer p) {
        return PLAYERS.computeIfAbsent(p.getUUID(),id->new Combat(p));
    }
    public static boolean stunned(LivingEntity entity) {
        Stun stun=STUNS.get(entity);
        if(stun==null)return false;
        if(entity.isRemoved() || entity.level().getGameTime()>=stun.until){STUNS.remove(entity);return false;}
        return true;
    }
    public static void clientStun(LivingEntity entity) {
        entity.stopUsingItem();
        STUNS.put(entity,new Stun(entity.position(),entity.level().getGameTime()+30));
    }
    static boolean visionActive(ServerPlayer p){return combat(p).visionStart>=0;}
    public static boolean busy(ServerPlayer p){return combat(p).skill!=0 || stunned(p);}
    static boolean spendDefenseResource(ServerPlayer player, StyleMode mode) {
        Combat state = combat(player);
        if (mode == StyleMode.SIMPLE) {
            if (!spendSimpleCharge(player, state, player.level().getGameTime())) return false;
        } else {
            if (state.energy < StyleDefense.ENERGY_COST) return false;
            state.energy -= StyleDefense.ENERGY_COST;
        }
        sync(player, state);
        return true;
    }

    private static boolean spendSimpleCharge(ServerPlayer player, Combat state, long now) {
        for (int charge = 0; charge < state.guardReady.length; charge++) {
            if (state.guardReady[charge] > now) continue;
            state.guardReady[charge] = now + StyleDefense.CHARGE_COOLDOWN_TICKS;
            player.getPersistentData().putLongArray(GUARD_CHARGES, state.guardReady);
            return true;
        }
        return false;
    }

    public static final int WAVE = 12, WIND = 13, CLOUD = 14, BOLT = 15;
    public static final int WAVE_IMPACT = 16;
    private static final double WAVE_SPEED = 8.0 / 6;
    private static int nextWaveId;
    public static final int PARRY_EFFECT = 10;
    static void parryEffect(ServerPlayer defender, Vec3 point, Vec3 direction) {
        var packet = new Effect(PARRY_EFFECT, point, direction, defender.getId());
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(defender, packet);
    }
    public static boolean consumeAwakening(ServerPlayer player) {
        Combat state = combat(player);
        if (state.awakeningHits < AWAKENING_REQUIRED_HITS) return false;
        state.awakeningHits = 0;
        state.swing = -1;
        sync(player, state);
        return true;
    }
    public static boolean suppressFalling(ServerPlayer player) { return combat(player).leapStart>=0; }
    public static boolean leapStrike(ServerPlayer player, Entity target) {
        Combat c=combat(player);
        return c.skill == 4 && !player.onGround() && !stunned(player)
                && (target == null || target instanceof LivingEntity living && validTarget(player, living));
    }
    public static void interruptLeap(ServerPlayer player) {
        Combat c=combat(player);c.skill=0;sync(player,c);
    }
    public static void stopNormalSweep(ServerPlayer player) {
        combat(player).swing = -1;
    }
    @SubscribeEvent public static void leapFall(LivingFallEvent event) {
        if(event.getEntity() instanceof ServerPlayer p && suppressFalling(p))event.setDamageMultiplier(0);
    }
    private static void sync(ServerPlayer p,Combat c) {
        p.getPersistentData().putInt(ENERGY,c.energy);
        p.getPersistentData().putLongArray("fightingarts_cooldowns",c.ready);
        p.getPersistentData().putInt(AWAKENING_HITS,c.awakeningHits);
        p.getPersistentData().putInt(AWAKENING_PERCENT,awakeningPercent(c));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, status(p, c));
    }

    private static Status status(ServerPlayer player, Combat state) {
        return new Status(player.getUUID(), state.energy, awakeningPercent(state), state.skill, state.start,
                state.ready[0], state.ready[1], state.ready[2], state.ready[3], state.ready[4], state.ready[5], state.ready[6], state.visionStart,state.visionEnd,state.visionExitTicks,leapFlight(state),state.preparationTicks,
                state.guardReady[0], state.guardReady[1], state.guardReady[2], state.guardReady[3], state.guardReady[4]);
    }

    private static int awakeningPercent(Combat state) {
        return state.awakeningHits * 100 / AWAKENING_REQUIRED_HITS;
    }

    @SubscribeEvent
    public static void registerDebugCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fightingarts")
                .then(Commands.literal("debug").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reset").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            Combat state = combat(player);
                            Arrays.fill(state.ready, 0);
                            Arrays.fill(state.guardReady, 0);
                            player.getPersistentData().putLongArray(GUARD_CHARGES, state.guardReady);
                            state.energy = MAX_ENERGY;
                            sync(player, state);
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Recargas zeradas. Energia: " + MAX_ENERGY + "/" + MAX_ENERGY), false);
                            return 1;
                        }))
                        .then(Commands.literal("awakening").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            Combat state = combat(player);
                            state.awakeningHits = AWAKENING_REQUIRED_HITS;
                            sync(player, state);
                            context.getSource().sendSuccess(() -> Component.literal("Barra de despertar: 100/100"), false);
                            return 1;
                        }))));
    }
    private static void effect(ServerPlayer p,int kind,Vec3 from,Vec3 to,int target) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p,new Effect(kind,from,to,target));
    }

    private static void use(ServerPlayer p,int id,float distance) {
        if(!Float.isFinite(distance))return;
        StyleState style=StyleService.current(p);
        Combat c=combat(p);
        long now=p.level().getGameTime();
        if(id==-5 && c.skill==5){endRush(p,c,now);return;}
        if(id==6 && c.visionStart>=0){endVision(p,c,now);return;}
        if(c.visionStart>=0 && id!=4)return;
        if(style==null || !style.mode().allowsSkill(id) || !style.phase().equals("ACTIVE")
                || !p.isAlive() || p.isSpectator() || p.isPassenger() || !p.getOffhandItem().isEmpty()
                || StyleService.findSwords(p).size()!=3 || stunned(p) || StyleDefense.disarmed(p)
                || StyleDefense.guarding(p) || (c.skill!=0 && c.skill!=4)
                || StyleService.isAttackLocked(style.phase(),style.combo(),now-style.comboStart()))return;
        if (now < c.ready[id-1]) {
            p.displayClientMessage(Component.literal("Habilidade em recarga (" + ((c.ready[id-1]-now+19)/20) + " s)."),true);
            return;
        }
        boolean simple = style.mode() == StyleMode.SIMPLE;
        if (!simple && c.energy < COST[id-1]) {
            p.displayClientMessage(Component.literal("Energia insuficiente ("+c.energy+"/"+COST[id-1]+")."),true);
            return;
        }
        LeapTrajectory.Plan leap = null;
        if (id == 4) {
            if(p.getAbilities().flying)return;
            leap = LeapTrajectory.aim(p, p.position(), p.getEyePosition(), p.getLookAngle(), style.mode());
        }
        if (simple && !spendSimpleCharge(p, c, now)) return;
        boolean quickDash = id == 1 && c.leapStart >= 0 && c.leapLanded < 0
                && now - c.leapStart >= 10;
        boolean leapDragon=id==7 && c.skill==4 && leapFlight(c);
        if (!simple) c.energy-=COST[id-1];
        if(id!=5 && id!=6)c.ready[id-1]=now+COOLDOWN[id-1]-(leapDragon?300:0);
        p.displayClientMessage(net.minecraft.network.chat.Component.literal(NAMES[id-1]),true);
        c.preparationTicks = id == 7 && !leapDragon ? 40 : id == 1 && !quickDash ? DASH_PREPARATION_TICKS : 0;
        c.awakened = style.mode() == StyleMode.AWAKENED;
        c.skill=id; c.start=now; c.anchor=p.position(); c.level=p.serverLevel();
        c.hit.clear();c.swing=-1;c.dashed=false;
        c.direction=Vec3.directionFromRotation(0,p.getYRot());
        p.stopUsingItem();
        if(id==5){
            var step=p.getAttribute(Attributes.STEP_HEIGHT);
            p.getAttribute(Attributes.MOVEMENT_SPEED).addTransientModifier(new AttributeModifier(RUSH_STEP,1,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            step.addTransientModifier(new AttributeModifier(RUSH_STEP, Math.max(0,1.05-step.getValue()),AttributeModifier.Operation.ADD_VALUE));
            c.rushCombo=0;
            c.rushHitReady.clear();
        }
        if(id==6){
            c.visionStart=now;
            c.visionEnd=-1;
            c.deepUntil=-1;
            p.getAttribute(Attributes.MOVEMENT_SPEED).addTransientModifier(
                    new AttributeModifier(SIGHT_SLOW,-.5,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        if(id==7){
            c.dragonDamage=(float)damage(p,7)*(leapDragon?.5f:1);
            if(leapDragon){
                c.dragonAim=dragonAim(p);
                launchDragon(p,c,now);
            }
        }
        if(leap!=null) {
            if(p.isFallFlying())p.stopFallFlying();
            c.leapStart=now;c.leapLanded=-1;
            // Effect 7 delivers the launch once to the controlling client. A second vanilla
            // hurt-motion packet could arrive a tick later and restart the vertical impulse.
            p.setOnGround(false);p.setDeltaMovement(leap.velocity());p.hurtMarked=false;p.hasImpulse=true;
            effect(p,7,p.position(),leap.velocity(),p.getId());
        }
        sound(p, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP, .5f, id==3 ? .65f : 1.2f);
        if (quickDash) dash(p,c,6,1);
        sync(p,c);
    }

    private static void sound(ServerPlayer player, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        player.level().playSound(null,player.blockPosition(),sound,net.minecraft.sounds.SoundSource.PLAYERS,volume,pitch);
    }
    private static Vec3 safeMove(ServerPlayer p,Vec3 desired) {
        return safeMove(p, desired, Set.of());
    }

    private static Vec3 safeMove(ServerPlayer p, Vec3 desired, Set<RelativeMovement> rotation) {
        Vec3 movement=desired.subtract(p.position());
        if(movement.lengthSqr()<1.0e-10 && rotation.isEmpty()){
            p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
            return p.position();
        }
        Vec3 allowed=Entity.collideBoundingBox(p,movement,p.getBoundingBox(),p.level(),
                p.level().getEntityCollisions(p,p.getBoundingBox().expandTowards(movement)));
        Vec3 end=p.position().add(allowed);
        if(!p.level().getWorldBorder().isWithinBounds(net.minecraft.core.BlockPos.containing(end))
                || !p.serverLevel().hasChunkAt(net.minecraft.core.BlockPos.containing(end)))return p.position();
        p.connection.teleport(end.x,end.y,end.z,p.getYRot(),p.getXRot(),rotation);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance=0;
        return end;
    }
    private static double swordDamage(ServerPlayer p,ItemStack sword) {
        var original=p.getAttribute(Attributes.ATTACK_DAMAGE);
        var attribute=new AttributeInstance(Attributes.ATTACK_DAMAGE,ignored->{});
        attribute.replaceFrom(original);
        p.getMainHandItem().forEachModifier(EquipmentSlot.MAINHAND,(type,modifier)->{
            if(type.equals(Attributes.ATTACK_DAMAGE))attribute.removeModifier(modifier.id());
        });
        sword.forEachModifier(EquipmentSlot.MAINHAND,(type,modifier)->{
            if(type.equals(Attributes.ATTACK_DAMAGE)){attribute.removeModifier(modifier.id());attribute.addTransientModifier(modifier);}
        });
        return attribute.getValue();
    }
    private static double damage(ServerPlayer p,int skill) {
        var swords=StyleService.findSwords(p);
        if(swords.size()!=3)return 0;
        double right=swordDamage(p,swords.get(0)),left=swordDamage(p,swords.get(1)),mouth=swordDamage(p,swords.get(2));
        return skillDamage(skill,right,left,mouth);
    }
    private static double skillDamage(int skill,double right,double left,double mouth){
        return switch(skill){case 1->(right+left+mouth)*1.35;case 2->left+right*.5;case 3->(right+left+mouth)*2;case 7->(right+left+mouth)*3.5;default->0;};
    }
    private static boolean validTarget(ServerPlayer p,LivingEntity target) {
        return target!=p && target.isAlive() && target.isAttackable() && !target.isSpectator()
                && !p.isAlliedTo(target) && (!(target instanceof Player other) || p.canHarmPlayer(other));
    }
    private static boolean visible(ServerPlayer p,Vec3 start,LivingEntity target) {
        return p.level().clip(new ClipContext(start,target.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getType()==HitResult.Type.MISS;
    }
    private static void strike(ServerPlayer p,Combat c,Vec3 start,Vec3 end,double radius,float damage,boolean fire) {
        AABB area=new AABB(start,end).inflate(radius);
        for(var target:p.level().getEntitiesOfClass(LivingEntity.class,area,t->validTarget(p,t)&&!c.hit.contains(t.getUUID()))) {
            AABB expanded=target.getBoundingBox().inflate(radius*.5);
            if(!expanded.contains(start) && expanded.clip(start,end).isEmpty())continue;
            Vec3 nearest=start.add(end.subtract(start).scale(Math.clamp(
                    target.position().subtract(start).dot(end.subtract(start))/Math.max(.001,start.distanceToSqr(end)),0,1)));
            if(!visible(p,nearest,target))continue;
            c.hit.add(target.getUUID());
            if (hurtWithSkill(p,c,target,damage,fire) && c.awakened && c.skill == 3 && !fire) {
                c.storms.put(target.getUUID(),p.level().getGameTime());
                effect(p,CLOUD,target.position(),Vec3.ZERO,target.getId());
            }
        }
    }

    private static boolean hurtWithSkill(ServerPlayer player, Combat combat, LivingEntity target, float damage, boolean fire) {
        combat.skillDamage = true;
        int immunity = target.invulnerableTime;
        // The delayed flame is a separate impact, even inside the dash's hurt cooldown.
        if (fire) target.invulnerableTime = 0;
        try {
            if (!target.hurt(player.damageSources().playerAttack(player), damage)) return false;
            StyleNetwork.sendDamageNumber(player, target.getBoundingBox().getCenter(), damage);
            return true;
        } finally {
            combat.skillDamage = false;
            if (fire) target.invulnerableTime = Math.max(immunity, target.invulnerableTime);
        }
    }

    /** A forward half-disc: sqrt(2) times melee reach doubles its horizontal area. */
    static boolean insideMoonSlash(Vec3 origin, Vec3 forward, double reach, AABB box) {
        double x = Math.clamp(origin.x, box.minX, box.maxX) - origin.x;
        double z = Math.clamp(origin.z, box.minZ, box.maxZ) - origin.z;
        double distanceSquared = x * x + z * z;
        if (distanceSquared > 2 * reach * reach || x * forward.x + z * forward.z < -0.05) return false;
        double height = origin.y + Math.sqrt(distanceSquared) * forward.y;
        return box.minY <= height + 0.75 && box.maxY >= height - 1.25;
    }

    private static void moonSlash(ServerPlayer player, Combat combat) {
        Vec3 eye = player.getEyePosition();
        Vec3 horizontal = Vec3.directionFromRotation(0, player.getYRot());
        Vec3 direction = horizontal.add(0, player.getLookAngle().y * 0.65, 0);
        double reach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        double radius = Math.sqrt(2) * reach;
        var weapons = StyleService.findSwords(player);
        if (weapons.size() != 3) return;
        float damage = (float)(damage(player, 2) * (combat.awakened ? 1.25 : 1));
        List<UUID> marked = new ArrayList<>();
        Vec3 aimEnd=eye.add(player.getLookAngle().scale(radius));
        boolean struckAimedTarget=false;
        for (var target : player.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(eye, eye).inflate(radius, radius + 1.25, radius), target -> validTarget(player, target))) {
            AABB box = target.getBoundingBox();
            if (!insideMoonSlash(eye, direction, reach, box) || !visible(player, eye, target)
                    || !hurtWithSkill(player, combat, target, damage, false)) continue;
            marked.add(target.getUUID());
            var pickBox=box.inflate(target.getPickRadius());
            if(pickBox.contains(eye) || pickBox.clip(eye,aimEnd).isPresent())struckAimedTarget=true;
            target.stopUsingItem();
            STUNS.put(target, new Stun(target.position(), target.level().getGameTime() + 30));
            Vec3 center = box.getCenter();
            Vec3 incoming = center.subtract(eye).multiply(1, 0, 1).normalize();
            if (incoming.lengthSqr() == 0) incoming = horizontal;
            effect(player, 2, center, incoming, target.getId());
        }

        Vec3 end = eye.add(direction.scale(radius));
        if (combat.awakened) {
            float damageLater = (float)(swordDamage(player,weapons.get(1))*1.5*1.25);
            combat.echo = new Echo(player.serverLevel(),player.level().getGameTime()+20,marked,damageLater,struckAimedTarget?List.of():cutBlocks(player,eye,end));
        }
        if(!struckAimedTarget)effect(player, 9, eye, end, -1);
        effect(player, 6, eye, end, player.getId());
    }

    /** Sweep the blade's arc through successive server ticks, keeping one hit per target. */
    public static void normalSweep(ServerPlayer p,int combo,Entity primary) {
        Combat c=combat(p);
        c.swing=p.level().getGameTime();c.combo=combo;c.swingYaw=p.getYRot();c.swingHits.clear();
        if(primary!=null)c.swingHits.add(primary.getUUID());
    }
    private static void sweepTick(ServerPlayer p,Combat c,long now) {
        long age=now-c.swing;
        if(c.swing<0 || age<1 || age>5)return;
        var swords=StyleService.findSwords(p);
        if(swords.size()!=3)return;
        float dmg=(float)(c.combo==1?swordDamage(p,swords.get(0)):c.combo==2?swordDamage(p,swords.get(1)):
                swordDamage(p,swords.get(0))+swordDamage(p,swords.get(1)));
        int first=c.combo==2?1:0,last=c.combo==1?0:1;
        for(int hand=first;hand<=last;hand++){
            Vec3[] previous=SwordSweepGeometry.blade(p,c.combo,hand,(age-1)/20.0,1);
            for(int step=1;step<=6;step++){
                Vec3[] next=SwordSweepGeometry.blade(p,c.combo,hand,(age-1+step/6.0)/20,1);
                for(int point=0;point<=4;point++){
                    Vec3 a=previous[0].lerp(previous[1],point/4.0),b=next[0].lerp(next[1],point/4.0);
                    for(var target:p.level().getEntitiesOfClass(LivingEntity.class,new AABB(a,b).inflate(.2),
                            entity->validTarget(p,entity)&&!c.swingHits.contains(entity.getUUID()))) {
                        var box=target.getBoundingBox().inflate(.18);
                        if(!box.contains(a)&&box.clip(a,b).isEmpty() || !visible(p,p.getEyePosition(),target))continue;
                        c.swingHits.add(target.getUUID());
                        target.hurt(p.damageSources().playerAttack(p),dmg);
                    }
                }
                previous=next;
            }
        }
    }
    @SubscribeEvent public static void tick(PlayerTickEvent.Post event) {
        if(!(event.getEntity() instanceof ServerPlayer p))return;
        Combat c=combat(p);
        long now=p.level().getGameTime();
        StyleState style=StyleService.current(p);
        boolean awakened = style != null && style.mode() == StyleMode.AWAKENED && style.phase().equals("ACTIVE")
                && now < style.awakeningEndsAt();
        long secondTick = now % 20;
        boolean recoveryTick = awakened ? secondTick == 0 || secondTick == 7 || secondTick == 14 : now % PASSIVE_INTERVAL == 0;
        if(recoveryTick && p.isAlive() && c.energy<MAX_ENERGY && !StyleDefense.guarding(p) && c.skill!=5 && c.visionStart<0) {
            c.energy++;
            sync(p,c);
        }
        if(style==null || !style.phase().equals("ACTIVE") || !p.isAlive() || c.level!=null&&c.level!=p.level()) {
            if(c.skill==5)endRush(p,c,now);
            if(c.visionStart>=0)endVision(p,c,now);
            c.deepUntil=-1;
            if(c.skill!=0){c.skill=0;sync(p,c);}
            if(c.leapStart>=0){c.leapStart=-1;sync(p,c);}
            c.swing=-1;
        } else {
            sweepTick(p,c,now);
            int age=(int)(now-c.start);
            if(c.skill==5)tickRush(p,c,now,age);
            if(c.visionStart>=0){
                long elapsed=now-c.visionStart;
                boolean drain = elapsed > 0 && elapsed % 2 == 0;
                if(drain)c.energy=Math.max(0,c.energy-1);
                if(stunned(p) || c.energy==0)endVision(p,c,now);
                else {
                    if(drain)sync(p,c);
                    if(elapsed%5==0)scanTargets(p,c);
                }
            }
            if(c.skill==7 && c.preparationTicks>0 && age==DRAGON_AIM_TICKS){
                c.dragonAim=dragonAim(p);
                c.direction=Vec3.directionFromRotation(0,p.getYRot());
            }
            if(c.skill==7 && c.preparationTicks>0 && age==c.preparationTicks)launchDragon(p,c,now);
            if(c.skill==1 && age>=c.preparationTicks && !c.dashed)dash(p,c,6,1);
            if(c.skill==2 && age==3)moonSlash(p,c);
            if(c.skill==3) {
                if(age>=13 && age<43 && (age-13)%10==0) sound(p,net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,.28f, .8f+(age-13)/30f);
                if (c.awakened && age >= 13 && age < 43) pullWind(p, age);
                if(age<43){c.direction=Vec3.directionFromRotation(0,p.getYRot());safeMove(p,c.anchor);}
                if(age==43){dash(p,c,10,3);c.anchor=p.position();}
                if(age>43)safeMove(p,c.anchor);
            }
            if(c.leapStart>=0) {
                p.fallDistance=0;
                if(now-c.leapStart>=2 && p.onGround() && c.leapLanded<0) {
                    c.leapLanded=now;
                    sound(p,net.minecraft.sounds.SoundEvents.PLAYER_SMALL_FALL,.6f,1.1f);
                    effect(p,8,p.position(),p.position(),p.getId());
                    if(c.skill==4)c.skill=0;
                    sync(p,c);
                }
                if(c.leapLanded>=0 && (now-c.leapLanded>=10 || !p.onGround()))c.leapStart=-1;
                if(c.leapStart>=0 && now-c.leapStart>160){c.leapStart=-1;if(c.skill==4)c.skill=0;sync(p,c);}
            }
            
            int duration=switch(c.skill){case 1->c.preparationTicks+25;case 2->12;case 3->103;case 4->180;case 6->22;case 7->c.preparationTicks==0?8:c.preparationTicks+1;default->Integer.MAX_VALUE;};
            if(age>=duration){c.skill=0;sync(p,c);}
        }
        if (p.isAlive() && c.level == p.level()) tickAftereffects(p,c,now);
        else { c.rushPushes.clear();c.dragonTick=-1;c.echo=null; c.waves.clear(); c.storms.clear(); }
        if(c.fireStart>=0) {
            long age=now-c.fireStart;
            if(c.level!=p.level() || age>=40 || !p.isAlive())c.fireStart=-1;
            else if(age>=0 && age%20==0) {
                c.hit.clear();
                int samples=Math.max(1,(int)Math.ceil(c.fireFrom.distanceTo(c.fireTo)*2));
                for(int i=0;i<=samples;i++){
                    Vec3 point=c.fireFrom.lerp(c.fireTo,i/(double)samples);
                    var floor=p.level().clip(new ClipContext(point.add(0,1,0),point.add(0,-3,0),
                            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
                    if(floor.getType()==HitResult.Type.MISS)continue;
                    Vec3 base=floor.getLocation().add(0,.1,0);
                    strike(p,c,base,base.add(0,1.3,0),1.1f,age==0?4:1,true);
                }
            }
        }
    }

    static void endAwakening(ServerPlayer player) {
        Combat state = combat(player);
        long now = player.level().getGameTime();
        if (state.skill == 5) endRush(player, state, now);
        if (state.visionStart >= 0) endVision(player, state, now);
        state.deepUntil = -1;
        if (state.skill == 6 || state.skill == 7) {
            state.skill = 0;
            sync(player, state);
        }
    }
    private static void dash(ServerPlayer p,Combat c,double distance,int kind) {
        c.dashed=true;
        sound(p,net.minecraft.sounds.SoundEvents.TRIDENT_THROW.value(),kind==3 ? .9f : .6f,kind==3 ? .65f : 1.1f);
        if(kind==3) sound(p,net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_KNOCKBACK,.9f,.7f);
        Vec3 from=p.position();
        c.direction=Vec3.directionFromRotation(0,p.getYRot());
        Vec3 requested=c.direction.scale(distance + (c.awakened && kind == 1 ? 5 : 0));
        Vec3 allowed=Entity.collideBoundingBox(p,requested,p.getBoundingBox(),p.level(),
                p.level().getEntityCollisions(p,p.getBoundingBox().expandTowards(requested)));
        double x=Math.abs(requested.x)>.0001?allowed.x/requested.x:1;
        double z=Math.abs(requested.z)>.0001?allowed.z/requested.z:1;
        Vec3 destination = from.add(requested.scale(Math.clamp(Math.min(x,z),0,1)));
        Vec3 to = safeMove(p, destination, kind == 1 ? Set.of(RelativeMovement.Y) : Set.of());
        strike(p,c,from.add(0,1,0),to.add(0,1,0),1.2f,(float)(damage(p,kind) * (kind == 1 && c.awakened ? 1.25 : 1)),false);
        effect(p,kind,from,to,p.getId());
        if (c.awakened && kind == 1) {
            Vec3 origin = to.add(0,p.getEyeHeight()*.78,0);
            c.waves.add(new Wave(nextWaveId++,p.serverLevel(),origin,p.getLookAngle(),
                    (float)(damage(p,1)*1.25),new HashSet<>(c.hit)));
        }
        if(kind==3){c.fireFrom=from;c.fireTo=to;c.fireStart=p.level().getGameTime()+12;}
    }
    @SubscribeEvent public static void stunTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre event) {
        if(!(event.getEntity() instanceof LivingEntity living) || living.level().isClientSide)return;
        Stun stun=STUNS.get(living);
        if(stun==null)return;
        if(!living.isAlive() || living.isRemoved() || living.level().getGameTime()>=stun.until){STUNS.remove(living);return;}
        living.stopUsingItem();
        living.setDeltaMovement(Vec3.ZERO);
        if(living instanceof ServerPlayer p)p.connection.teleport(stun.anchor.x,stun.anchor.y,stun.anchor.z,p.getYRot(),p.getXRot());
        else {living.setPos(stun.anchor);if(living instanceof Mob mob)mob.getNavigation().stop();}
    }
    @SubscribeEvent public static void gain(LivingDamageEvent.Post event) {
        if(event.getNewDamage()<=0 || !(event.getSource().getEntity() instanceof ServerPlayer p))return;
        StyleState state=StyleService.current(p);
        if(state==null || !state.phase().equals("ACTIVE"))return;
        Combat c=combat(p);
        c.energy=Math.min(MAX_ENERGY,c.energy+(c.skillDamage?SKILL_HIT_ENERGY:NORMAL_HIT_ENERGY));
        if (state.mode() != StyleMode.AWAKENED)
            c.awakeningHits=Math.min(AWAKENING_REQUIRED_HITS,c.awakeningHits+1);
        sync(p,c);
    }
    @SubscribeEvent public static void killed(LivingDeathEvent event) {
        if(event.getEntity() instanceof Monster && event.getSource().getEntity() instanceof ServerPlayer p){
            Combat c=combat(p);c.energy=Math.min(MAX_ENERGY,c.energy+MONSTER_KILL_ENERGY);sync(p,c);
        }
        STUNS.remove(event.getEntity());
        if(event.getEntity() instanceof ServerPlayer p){Combat c=combat(p);if(c.skill==5)endRush(p,c,p.level().getGameTime());if(c.visionStart>=0)endVision(p,c,p.level().getGameTime());c.skill=0;c.fireStart=-1;sync(p,c);}
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer p){
            Combat c=combat(p);
            if(c.skill==5)endRush(p,c,p.level().getGameTime());
            if(c.visionStart>=0)endVision(p,c,p.level().getGameTime());
        }
        PLAYERS.remove(event.getEntity().getUUID());STUNS.remove(event.getEntity());
    }
    @SubscribeEvent public static void unload(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if(event.getEntity() instanceof LivingEntity living)STUNS.remove(living);
    }
    @SubscribeEvent public static void clone(PlayerEvent.Clone event) {
        event.getEntity().getPersistentData().putInt(ENERGY,event.getOriginal().getPersistentData().getInt(ENERGY));
        event.getEntity().getPersistentData().putLongArray("fightingarts_cooldowns",event.getOriginal().getPersistentData().getLongArray("fightingarts_cooldowns"));
        event.getEntity().getPersistentData().putLongArray(GUARD_CHARGES,event.getOriginal().getPersistentData().getLongArray(GUARD_CHARGES));
        event.getEntity().getPersistentData().putInt(AWAKENING_PERCENT,event.getOriginal().getPersistentData().getInt(AWAKENING_PERCENT));
        if (event.getOriginal().getPersistentData().contains(AWAKENING_HITS)) {
            event.getEntity().getPersistentData().putInt(AWAKENING_HITS,event.getOriginal().getPersistentData().getInt(AWAKENING_HITS));
        }
        PLAYERS.remove(event.getEntity().getUUID());
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if(event.getEntity() instanceof ServerPlayer p)sync(p,combat(p));
    }
    @SubscribeEvent public static void tracking(PlayerEvent.StartTracking event) {
        if(event.getEntity() instanceof ServerPlayer watcher && event.getTarget() instanceof ServerPlayer p){
            Combat c=combat(p);
            PacketDistributor.sendToPlayer(watcher,status(p,c));
        }
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        PLAYERS.clear();STUNS.clear();
    }
    private record Stun(Vec3 anchor,long until){}
    private static final int FRACTURE_LAYER_INTERVAL = 3;
    private record Fracture(BlockPos pos, BlockState state, int depth) {}
    private record Echo(ServerLevel level, long due, List<UUID> targets, float damage, List<Fracture> blocks) {}

    private static final class Wave {
        final int id;
        final ServerLevel level;
        final Vec3 velocity;
        final float damage;
        final Set<UUID> hits;
        Vec3 position;

        Wave(int id, ServerLevel level, Vec3 origin, Vec3 direction, float damage, Set<UUID> hits) {
            this.id = id;
            this.level = level;
            this.position = origin;
            this.velocity = direction.scale(WAVE_SPEED);
            this.damage = damage;
            this.hits = hits;
        }
    }

    private static List<Fracture> cutBlocks(ServerPlayer player, Vec3 eye, Vec3 end) {
        var level = player.serverLevel();
        var state = combat(player);
        int depth = state.deepUntil >= state.start ? 10 : 2;
        var found=new LinkedHashMap<BlockPos,Fracture>();
        for (var patch : MoonSlashSurface.project(player,eye,end)) {
            var pos = patch.block();
            for(int layer=0;layer<depth;layer++,pos=pos.relative(patch.face().getOpposite())){
                if(!level.hasChunkAt(pos))break;
                var material=level.getBlockState(pos);
                if(!canFracture(player,pos,material))break;
                var old=found.get(pos);
                if(old==null || layer<old.depth)found.put(pos,new Fracture(pos,material,layer));
            }
        }
        return new ArrayList<>(found.values());
    }

    public static final int RUSH=17, DRAGON=18, DRAGON_IMPACT=19, DRAGON_HIT=20;
    private static final int RUSH_REPEAT_TICKS = 20;
    private static final ResourceLocation RUSH_STEP=ResourceLocation.fromNamespaceAndPath("fightingarts","rush_step");

    public static boolean sightReveals(Player observer, LivingEntity target) {
        double distance = observer.distanceToSqr(target);
        return distance <= SIGHT_RANGE * SIGHT_RANGE
                && (distance <= SIGHT_THROUGH_WALLS * SIGHT_THROUGH_WALLS || observer.hasLineOfSight(target));
    }

    private static void scanTargets(ServerPlayer p,Combat c){
        var swords=StyleService.findSwords(p);
        if(swords.size()!=3)return;
        // Compare the weaker hand's normal strike with the strongest single hit in the awakened kit.
        float weakest=(float)Math.min(swordDamage(p,swords.get(0)),swordDamage(p,swords.get(1)));
        float strongest=(float)damage(p,7);
        float typicalHit=(weakest+strongest)*.5f;
        var strikeSource=p.damageSources().playerAttack(p);
        float playerHealth=p.getHealth()+p.getAbsorptionAmount();
        Set<Integer> seen=new HashSet<>();
        for(var mob:p.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(SIGHT_RANGE),
                e->e!=p && e.isAlive() && !e.isSpectator())){
            if(!sightReveals(p,mob))continue;
            DamageReadout threat=maximumThreat(p,mob);
            int hitsToDefeatPlayer=threat.afterDefense>0
                    ? Math.max(1,(int)Math.ceil(playerHealth/threat.afterDefense)) : 0;
            int tint=SIGHT_YELLOW;
            if(validTarget(p,mob)){
                float effectiveHit=(predictedDamage(p.serverLevel(),mob,strikeSource,weakest)
                        +predictedDamage(p.serverLevel(),mob,strikeSource,strongest))*.5f;
                tint=sightThreatTint(typicalHit,effectiveHit,mob.getHealth()+mob.getAbsorptionAmount(),playerHealth,threat.afterDefense,hitsToDefeatPlayer);
            }
            var report=new SightTarget(mob.getId(),threat.raw,threat.afterDefense,tint);
            seen.add(mob.getId());
            if(!report.equals(c.sightTargets.put(mob.getId(),report)))PacketDistributor.sendToPlayer(p,report);
        }
        var previous=c.sightTargets.keySet().iterator();
        while(previous.hasNext()){
            int entity=previous.next();
            if(seen.contains(entity))continue;
            PacketDistributor.sendToPlayer(p,new SightTarget(entity,0,0,0));
            previous.remove();
        }
    }
    private record DamageReadout(float raw,float afterDefense) {}

    private static double unmodifiedWeaponDamage(double base,ItemStack item){
        var attack=new AttributeInstance(Attributes.ATTACK_DAMAGE,ignored->{});
        attack.setBaseValue(base);
        item.forEachModifier(EquipmentSlot.MAINHAND,(type,modifier)->{
            if(type.equals(Attributes.ATTACK_DAMAGE))attack.addOrUpdateTransientModifier(modifier);
        });
        return attack.getValue();
    }

    static float playerThreat(ServerPlayer player){
        double base=player.getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue();
        var slots=StyleService.findSwords(player);
        var style=StyleService.current(player);
        if(style!=null && "ACTIVE".equals(style.phase()) && style.mode().hasSkills() && slots.size()==3){
            double right=unmodifiedWeaponDamage(base,slots.get(0));
            double left=unmodifiedWeaponDamage(base,slots.get(1));
            double mouth=unmodifiedWeaponDamage(base,slots.get(2));
            double dash=skillDamage(1,right,left,mouth), cut=skillDamage(2,right,left,mouth), storm=skillDamage(3,right,left,mouth);
            if(style.mode()==StyleMode.COMMON)return (float)((dash+cut+storm)/3);
            double rush=(right+left)*.75;
            double dragon=skillDamage(7,right,left,mouth);
            return (float)((1.25*dash+1.25*cut+left*1.875+storm+rush+dragon)/5);
        }
        double best=base;
        var inventory=player.getInventory();
        for(int slot=0;slot<inventory.getContainerSize();slot++){
            var stack=inventory.getItem(slot);
            if(!stack.isEmpty())best=Math.max(best,unmodifiedWeaponDamage(base,stack));
        }
        double styleDamage=slots.stream().mapToDouble(stack->unmodifiedWeaponDamage(base,stack)).sum();
        return (float)Math.max(best,styleDamage);
    }

    private static DamageReadout maximumThreat(ServerPlayer observer,LivingEntity mob) {
        if(mob instanceof ServerPlayer player)
            return threatHit(observer,player.damageSources().playerAttack(player),playerThreat(player));
        var sources=mob.damageSources();
        var meleeSource=mob instanceof Player other?sources.playerAttack(other):sources.mobAttack(mob);
        var attack=mob.getAttribute(Attributes.ATTACK_DAMAGE);
        float melee=attack==null?0:(float)attack.getValue();
        if(mob instanceof net.minecraft.world.entity.animal.IronGolem && melee>=1)melee=melee/2+(int)melee-1;
        var best=threatHit(observer,meleeSource,melee);
        var alternateSource=meleeSource;
        float alternate=0;
        if(mob instanceof net.minecraft.world.entity.monster.Creeper creeper){
            alternate=creeper.isPowered()?85:43;
            alternateSource=sources.explosion(creeper,creeper);
        }else if(mob instanceof net.minecraft.world.entity.monster.Ghast ghast){
            alternate=14*ghast.getExplosionPower()+1;
            alternateSource=sources.explosion(null,ghast);
        }else if(mob instanceof net.minecraft.world.entity.monster.warden.Warden){
            alternate=10;
            alternateSource=sources.sonicBoom(mob);
        }else if(mob instanceof net.minecraft.world.entity.monster.Witch){
            alternate=6;
            alternateSource=sources.indirectMagic(null,mob);
        }else if(mob instanceof net.minecraft.world.entity.monster.Evoker){
            alternate=6;
            alternateSource=sources.indirectMagic(null,mob);
        }else if(mob instanceof net.minecraft.world.entity.boss.wither.WitherBoss){
            alternate=15;
            alternateSource=sources.explosion(null,mob);
        }else if(mob instanceof net.minecraft.world.entity.monster.Shulker){
            alternate=4;
            alternateSource=sources.mobProjectile(null,mob);
        }else if(mob instanceof net.minecraft.world.entity.monster.Blaze){
            alternate=5;
            alternateSource=sources.mobProjectile(null,mob);
        }else if(mob.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem){
            alternate=10;
            alternateSource=sources.arrow(null,mob);
        }
        if(alternate<=0)return best;
        var special=threatHit(observer,alternateSource,alternate);
        return new DamageReadout(Math.max(best.raw,special.raw),Math.max(best.afterDefense,special.afterDefense));
    }

    private static DamageReadout threatHit(ServerPlayer player,net.minecraft.world.damagesource.DamageSource source,float damage) {
        if(damage>0 && source.scalesWithDifficulty())damage=switch(player.level().getDifficulty()){
            case HARD->damage*1.5f;
            case EASY->Math.min(damage,damage*.5f+1);
            case PEACEFUL->0;
            default->damage;
        };
        return new DamageReadout(damage,predictedDamage(player.serverLevel(),player,source,damage));
    }

    static int sightThreatTint(float typicalHit,float effectiveHit,float mobHealth,float playerHealth,float incomingDamage,int hitsToDefeatPlayer) {
        if(hitsToDefeatPlayer==1 || hitsToDefeatPlayer==2)return SIGHT_RED;
        if(hitsToDefeatPlayer==3)return SIGHT_YELLOW;
        float hitsToDefeat=mobHealth/Math.max(.01f,effectiveHit);
        float healthLostPerHit=incomingDamage/Math.max(1,playerHealth);
        if(hitsToDefeat<=1 && healthLostPerHit<.30f)return SIGHT_WHITE;

        // Glass cannons can be dangerous for one hit, but their low health caps their threat.
        boolean fragile=mobHealth<playerHealth && effectiveHit>=typicalHit*.80f;
        if(incomingDamage<=0 || fragile || hitsToDefeat<=1)return SIGHT_YELLOW;

        // A weak attacker becomes dangerous if it survives enough strikes to retaliate repeatedly.
        return healthLostPerHit*hitsToDefeat>=.42f?SIGHT_RED:SIGHT_YELLOW;
    }

    private static float predictedDamage(ServerLevel level, LivingEntity mob, net.minecraft.world.damagesource.DamageSource source, float raw){
        if(raw<=0 || mob.isInvulnerableTo(source))return 0;
        float after=raw;
        if(!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR)){
            after=net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(mob,after,source,
                    mob.getArmorValue(),(float)mob.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        }
        var resist=mob.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
        if(resist!=null && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_RESISTANCE)
                && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_EFFECTS)){
            after*=Math.max(0,1-.2f*(1+resist.getAmplifier()));
        }
        if(source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ENCHANTMENTS)
                || source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_EFFECTS))return after;
        return net.minecraft.world.damagesource.CombatRules.getDamageAfterMagicAbsorb(after,
                net.minecraft.world.item.enchantment.EnchantmentHelper.getDamageProtection(level,mob,source));
    }
    private static void endVision(ServerPlayer p, Combat c,long now){
        p.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(SIGHT_SLOW);
        c.visionEnd=now;
        c.visionExitTicks=(int)Math.clamp(now-c.visionStart,0,20);
        c.visionStart=-1;
        c.deepUntil=now+200;
        c.sightTargets.clear();
        c.ready[5]=now+COOLDOWN[5];sync(p,c);
    }

    private static void endRush(ServerPlayer p,Combat c,long now){
        p.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(RUSH_STEP);
        p.getAttribute(Attributes.STEP_HEIGHT).removeModifier(RUSH_STEP);
        c.skill=0;c.ready[4]=now+COOLDOWN[4];
        c.rushHitReady.clear();
        p.setDeltaMovement(0,p.getDeltaMovement().y,0);p.hurtMarked=true;
        sync(p,c);
    }

    private static void tickRush(ServerPlayer p,Combat c,long now,int age){
        if(age>=200 || stunned(p) || StyleDefense.disarmed(p) || age>0 && age%2==0 && c.energy==0){
            endRush(p,c,now);return;
        }
        if(age>0 && age%2==0){c.energy--;sync(p,c);}
        Vec3 look=Vec3.directionFromRotation(0,p.getYRot());
        Vec3 previous=c.anchor;
        c.anchor=p.position();
        if(age%3==0)effect(p,RUSH,p.position(),look,p.getId());
        var swords=StyleService.findSwords(p);
        if(swords.size()!=3){endRush(p,c,now);return;}
        for(var target:p.level().getEntitiesOfClass(LivingEntity.class,new AABB(previous,c.anchor).inflate(1.15,1.5,1.15),
                t->validTarget(p,t) && c.rushHitReady.getOrDefault(t.getUUID(),0L)<=now)){
            if(!visible(p,p.getEyePosition(),target))continue;
            c.rushHitReady.put(target.getUUID(),now+RUSH_REPEAT_TICKS);
            int hand=c.rushCombo++%2;
            StyleService.rushSwing(p,hand+1);
            if(hurtWithSkill(p,c,target,(float)(swordDamage(p,swords.get(hand))*1.5),false)){
                // Distribute seven horizontal blocks over a short ease-out. Gravity and block collision stay vanilla.
                c.rushPushes.put(target.getUUID(),new RushPush(target.position(),look,now));
            }
        }
    }

    private record RushPush(Vec3 origin, Vec3 look, long start) {}
    private static void tickRushPushes(ServerPlayer caster, Combat combat, long now){
        combat.rushPushes.entrySet().removeIf(entry->{
            if(!(caster.serverLevel().getEntity(entry.getKey()) instanceof LivingEntity target) || !target.isAlive())return true;
            var push=entry.getValue();
            long age=now-push.start;
            double t=Math.clamp((age+1)/12.0,0,1);
            double travelled=target.position().subtract(push.origin).dot(push.look);
            double speed=age>=14?0:Math.clamp(7*t*(2-t)-travelled,0,1.25);
            Vec3 displacement=push.look.scale(speed);
            if(target instanceof ServerPlayer){
                target.setDeltaMovement(displacement.add(0,target.getDeltaMovement().y,0));
                target.hurtMarked=true;
            }else{
                target.move(MoverType.SELF,displacement);
                target.setDeltaMovement(0,target.getDeltaMovement().y,0);
            }
            return age>=14 || travelled>=6.995;
        });
    }

    private static Vec3 dragonAim(ServerPlayer caster) {
        return caster.getEyePosition().add(caster.getLookAngle().scale(DRAGON_REACH));
    }

    private static void launchDragon(ServerPlayer player,Combat combat,long now){
        Vec3 eye=player.getEyePosition(), delta=combat.dragonAim.subtract(eye);
        if(delta.lengthSqr()>DRAGON_REACH*DRAGON_REACH)combat.dragonAim=eye.add(delta.normalize().scale(DRAGON_REACH));
        Vec3 heading=combat.dragonAim.subtract(player.position()).multiply(1,0,1).normalize();
        if(heading.lengthSqr()<.001)heading=combat.direction;
        combat.dragonOrigin=player.position().subtract(heading.scale(2.7)).add(0,-.85,0);
        combat.dragonTick=now;
        combat.dragonHits.clear();
        combat.dragonMinimumDamage=combat.dragonDamage*.4f;
        effect(player,DRAGON,combat.dragonOrigin,combat.dragonAim,player.getId());
    }

    private record DragonContact(LivingEntity target,Vec3 head,Vec3 surface) {}

    private static void advanceDragon(ServerPlayer player,Combat combat,int tick){
        if(tick<=0)return;
        Vec3 previous=DragonFlight.point(combat.dragonOrigin,combat.dragonAim,tick-1);
        Vec3 to=DragonFlight.point(combat.dragonOrigin,combat.dragonAim,tick);
        double emergence=combat.dragonOrigin.y+.90;
        if(tick<=10 && to.y<=emergence)return;
        Vec3 from=tick<=10 && previous.y<emergence
                ? previous.lerp(to,(emergence-previous.y)/(to.y-previous.y)) : previous;
        var level=player.serverLevel();
        var wall=level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        Vec3 end=wall.getLocation();
        AABB sweep=new AABB(from,end).inflate(DRAGON_HEAD_WIDTH,DRAGON_HEAD_HEIGHT,DRAGON_HEAD_WIDTH);
        var contacts=new ArrayList<DragonContact>();
        for(var target:level.getEntitiesOfClass(LivingEntity.class,sweep,e->validTarget(player,e)
                && !combat.dragonHits.contains(e.getUUID()))){
            var body=target.getBoundingBox();
            var expanded=body.inflate(DRAGON_HEAD_WIDTH,DRAGON_HEAD_HEIGHT,DRAGON_HEAD_WIDTH);
            var contact=expanded.contains(from)?Optional.of(from):expanded.clip(from,end);
            if(contact.isEmpty())continue;
            Vec3 head=contact.get();
            Vec3 surface=new Vec3(Math.clamp(head.x,body.minX,body.maxX),
                    Math.clamp(head.y,body.minY,body.maxY),Math.clamp(head.z,body.minZ,body.maxZ));
            if(level.clip(new ClipContext(head,surface,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player))
                    .getType()==HitResult.Type.MISS)contacts.add(new DragonContact(target,head,surface));
        }
        contacts.sort(Comparator.comparingDouble(hit->hit.head.distanceToSqr(from)+.001*hit.surface.distanceToSqr(hit.head)));
        for(var hit:contacts){
            combat.dragonHits.add(hit.target.getUUID());
            float health=hit.target.getHealth();
            if(hurtWithSkill(player,combat,hit.target,combat.dragonDamage,false)){
                combat.dragonDamage=dragonDamageAfterHit(combat.dragonDamage,combat.dragonMinimumDamage,health);
                effect(player,DRAGON_HIT,hit.surface,Vec3.ZERO,player.getId());
            }
        }
        boolean struckWall=wall.getType()==HitResult.Type.BLOCK;
        if(!struckWall && tick<DragonFlight.duration(combat.dragonOrigin,combat.dragonAim))return;
        effect(player,DRAGON_IMPACT,end,to.subtract(from).normalize(),player.getId());
        if(struckWall){
            level.playSound(null,end.x,end.y,end.z,net.minecraft.sounds.SoundEvents.DRAGON_FIREBALL_EXPLODE,
                    net.minecraft.sounds.SoundSource.PLAYERS,1f,.72f);
            var debris=new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                    level.getBlockState(wall.getBlockPos()));
            level.sendParticles(debris,end.x,end.y,end.z,28,.55,.38,.55,.13);
        }
        combat.dragonTick=-1;
    }

    static float dragonDamageAfterHit(float damage,float minimum,float targetHealth){
        float loss=Math.clamp(targetHealth*.005f,.01f,.10f);
        return Math.max(minimum,damage*(1-loss));
    }

    private static void pullWind(ServerPlayer p, int age) {
        Vec3 front = Vec3.directionFromRotation(0,p.getYRot());
        if (age == 13) effect(p, WIND, p.position(), front, p.getId());
        for (var target : p.level().getEntitiesOfClass(LivingEntity.class,
                p.getBoundingBox().expandTowards(front.scale(10)).inflate(4,2,4), t -> validTarget(p,t))) {
            Vec3 offset = target.position().subtract(p.position());
            double along = offset.dot(front);
            if (along < 0 || along > 10 || offset.subtract(front.scale(along)).lengthSqr() > 16
                    || !visible(p,p.getEyePosition(),target)) continue;
            Vec3 drag = p.position().add(front.scale(Math.clamp(along,1,9))).subtract(target.position());
            Vec3 step = drag.scale(.15);
            if (target instanceof ServerPlayer) {
                target.push(step.x, Math.clamp(step.y,-.1,.1), step.z);
                target.hurtMarked = true;
            } else {
                target.move(MoverType.SELF,step);
            }
        }
    }

    private static void tickAftereffects(ServerPlayer p, Combat c, long now) {
        var level = p.serverLevel();
        tickRushPushes(p,c,now);
        if(c.dragonTick>=0)advanceDragon(p,c,(int)(now-c.dragonTick));
        c.waves.removeIf(wave -> advanceWave(p,c,wave));
        if (c.echo != null) tickEcho(p,c,now);
        c.storms.entrySet().removeIf(entry -> {
            if (!(level.getEntity(entry.getKey()) instanceof LivingEntity target) || !validTarget(p,target)) return true;
            long age = now-entry.getValue();
            if (age == 8 || age == 18 || age == 28) {
                Vec3 hit = target.getBoundingBox().getCenter();
                effect(p, BOLT, hit.add(0,target.getBbHeight()*.5+3,0), hit, target.getId());
                hurtWithSkill(p,c,target,5,true);
            }
            return age >= 28;
        });
    }

    /** Advances through loaded terrain and reports when the slash has finished. */
    private static boolean advanceWave(ServerPlayer player, Combat combat, Wave wave) {
        var level = wave.level;
        if (level != player.level()) return true;
        Vec3 from = wave.position;
        Vec3 next = from.add(wave.velocity);
        var segment = new AABB(from,next);
        if (!level.getWorldBorder().isWithinBounds(BlockPos.containing(next))
                || !level.hasChunksAt(BlockPos.containing(segment.minX,segment.minY,segment.minZ),
                        BlockPos.containing(segment.maxX,segment.maxY,segment.maxZ))) return true;

        var contact = level.clip(new ClipContext(from,next,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        boolean impact = contact.getType() == HitResult.Type.BLOCK;
        Vec3 to = impact ? contact.getLocation() : next;
        for (var target : level.getEntitiesOfClass(LivingEntity.class,new AABB(from,to).inflate(1.15),
                entity -> validTarget(player,entity) && !wave.hits.contains(entity.getUUID()))) {
            if (!visible(player,from,target)) continue;
            wave.hits.add(target.getUUID());
            hurtWithSkill(player,combat,target,wave.damage,false);
        }

        // Send to observers along the path, including players too far away to track the caster.
        PacketDistributor.sendToPlayersNear(level,null,from.x,from.y,from.z,64,
                new Effect(impact ? WAVE_IMPACT : WAVE,from,to,wave.id));
        wave.position = to;
        return impact;
    }

    public static boolean cuttableMaterial(net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
                && state.getDestroySpeed(level,pos) >= 0;
    }

    private static boolean canFracture(ServerPlayer player, BlockPos pos, BlockState state) {
        var level = player.serverLevel();
        return cuttableMaterial(level,pos,state) && level.mayInteract(player,pos)
                && level.getWorldBorder().isWithinBounds(pos)
                && !player.blockActionRestricted(level,pos,player.gameMode.getGameModeForPlayer());
    }

    private static void tickEcho(ServerPlayer player, Combat combat, long now) {
        var echo = combat.echo;
        var level = echo.level;
        if (level != player.level()) { combat.echo = null; return; }
        long time = now - echo.due;
        if (time < 0) return;
        if (time == 0) {
            for (var id : echo.targets)
                if (level.getEntity(id) instanceof LivingEntity target && validTarget(player,target))
                    hurtWithSkill(player,combat,target,echo.damage,true);
        }
        var iterator = echo.blocks.iterator();
        while (iterator.hasNext()) {
            var block = iterator.next();
            if (time < block.depth * FRACTURE_LAYER_INTERVAL) continue;
            if (level.hasChunkAt(block.pos) && level.getBlockState(block.pos) == block.state) {
                if (canFracture(player,block.pos,block.state)) {
                    combat.breaking = block.pos;
                    try {
                        if (player.gameMode.destroyBlock(block.pos) && level.getBlockState(block.pos) != block.state) {
                            // Vanilla broadcasts the break sound and debris to everyone except the miner.
                            player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelEventPacket(
                                    2001,block.pos,net.minecraft.world.level.block.Block.getId(block.state),false));
                        }
                    } finally { combat.breaking = null; }
                }
            }
            iterator.remove();
        }
        if (echo.blocks.isEmpty()) combat.echo = null;
    }

    static boolean cuttingBlock(ServerPlayer p, BlockPos pos) {
        return pos.equals(combat(p).breaking);
    }

    private static final class Combat {
        int energy,awakeningHits,skill,combo,preparationTicks;long start,swing=-1,fireStart=-1,leapStart=-1,leapLanded=-1;
        boolean awakened;
        long visionStart=-1,visionEnd=-1,deepUntil=-1,dragonTick=-1;
        int visionExitTicks;
        int rushCombo;
        final Map<UUID,Long> rushHitReady=new HashMap<>();
        final Map<UUID,RushPush> rushPushes=new HashMap<>();
        final Map<Integer,SightTarget> sightTargets=new HashMap<>();
        Vec3 dragonOrigin,dragonAim;
        float dragonDamage, dragonMinimumDamage;
        final Set<UUID> dragonHits=new HashSet<>();
        Echo echo;
        final List<Wave> waves = new ArrayList<>();
        final Map<UUID, Long> storms = new HashMap<>();
        BlockPos breaking;
        final long[] ready=new long[7];float swingYaw;boolean skillDamage,dashed;
        final long[] guardReady = new long[SIMPLE_CHARGES];
        Vec3 anchor=Vec3.ZERO,direction=Vec3.ZERO,fireFrom=Vec3.ZERO,fireTo=Vec3.ZERO;
        ServerLevel level;
        final Set<UUID> hit=new HashSet<>(),swingHits=new HashSet<>();
        Combat(ServerPlayer p){
            energy=Math.clamp(p.getPersistentData().getInt(ENERGY),0,MAX_ENERGY);
            var data = p.getPersistentData();
            int savedHits = data.contains(AWAKENING_HITS) ? data.getInt(AWAKENING_HITS)
                    : Math.round(data.getInt(AWAKENING_PERCENT) * (AWAKENING_REQUIRED_HITS / 100f));
            awakeningHits = Math.clamp(savedHits, 0, AWAKENING_REQUIRED_HITS);
            long[] saved=p.getPersistentData().getLongArray("fightingarts_cooldowns");
            System.arraycopy(saved,0,ready,0,Math.min(saved.length,ready.length));
            long[] savedGuard = p.getPersistentData().getLongArray(GUARD_CHARGES);
            System.arraycopy(savedGuard, 0, guardReady, 0, Math.min(savedGuard.length, guardReady.length));
        }
    }
}
