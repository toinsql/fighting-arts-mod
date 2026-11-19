package fightingarts;

import java.util.*;
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
    public static final int[] COST = {25,15,75,20};
    public static final int[] COOLDOWN = {200,300,800,60};
    public static final String[] NAMES = {"Investida das Três Presas","Fenda da Lua Sombria",
            "Vendaval do Demônio","Salto da Garça Rubra"};
    public static final int MAX_ENERGY = 200;
    public static final int PASSIVE_INTERVAL = 10;
    public static final int NORMAL_HIT_ENERGY = 2;
    public static final int SKILL_HIT_ENERGY = 16;
    public static final int MONSTER_KILL_ENERGY = 12;
    public static final int DASH_PREPARATION_TICKS = 15;
    public static final double SPIN_START = 0.66667;
    public static final double SPIN_END = 2.125;
    private static final String ENERGY = "fightingarts_energy";
    private static final Map<UUID, Combat> PLAYERS = new HashMap<>();
    private static final Map<LivingEntity, Stun> STUNS = new java.util.concurrent.ConcurrentHashMap<>();
    public static Consumer<Status> clientStatus = status -> {};
    public static Consumer<Effect> clientEffect = effect -> {};
    private SantoryuSkills() {}

    public record Status(UUID player, int energy, int awakening, int skill, long start,
                         long ready1, long ready2, long ready3, long ready4, int preparationTicks) implements CustomPacketPayload {
        public static final Type<Status> TYPE = SantoryuSkills.type("skill_status");
        static final StreamCodec<RegistryFriendlyByteBuf,Status> CODEC = StreamCodec.of((b,s)->{
            b.writeUUID(s.player); b.writeVarInt(s.energy); b.writeVarInt(s.awakening);
            b.writeVarInt(s.skill); b.writeLong(s.start);
            b.writeLong(s.ready1); b.writeLong(s.ready2); b.writeLong(s.ready3); b.writeLong(s.ready4); b.writeVarInt(s.preparationTicks);
        },b->new Status(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readLong(),
                b.readLong(),b.readLong(),b.readLong(),b.readLong(),b.readVarInt()));
        public Type<Status> type(){return TYPE;}
        public long ready(int index) {
            return switch(index){case 0->ready1;case 1->ready2;case 2->ready3;default->ready4;};
        }
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
            var r=event.registrar("1");
            r.playToServer(Request.TYPE,Request.CODEC,(p,c)->c.enqueueWork(()->{
                if(c.player() instanceof ServerPlayer player) use(player,p.skill,p.distance);
            }));
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
    public static boolean busy(ServerPlayer p){return combat(p).skill!=0 || stunned(p);}
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
        p.getPersistentData().putInt("fightingarts_awakening",c.awakening);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p,new Status(p.getUUID(),c.energy,c.awakening,c.skill,c.start,
                c.ready[0],c.ready[1],c.ready[2],c.ready[3],c.preparationTicks));
    }

    @SubscribeEvent
    public static void registerDebugCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fightingarts")
                .then(Commands.literal("debug").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reset").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            Combat state = combat(player);
                            Arrays.fill(state.ready, 0);
                            state.energy = MAX_ENERGY;
                            sync(player, state);
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Recargas zeradas. Energia: " + MAX_ENERGY + "/" + MAX_ENERGY), false);
                            return 1;
                        }))));
    }
    private static void effect(ServerPlayer p,int kind,Vec3 from,Vec3 to,int target) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p,new Effect(kind,from,to,target));
    }

    private static void use(ServerPlayer p,int id,float distance) {
        if(id<1 || id>4 || !Float.isFinite(distance))return;
        StyleState style=StyleService.current(p);
        Combat c=combat(p);
        long now=p.level().getGameTime();
        if(style==null || style.mode()!=StyleMode.COMMON || !style.phase().equals("ACTIVE")
                || !p.isAlive() || p.isSpectator() || p.isPassenger() || !p.getOffhandItem().isEmpty()
                || StyleService.findSwords(p).size()!=3 || stunned(p) || (c.skill!=0 && c.skill!=4)
                || StyleService.isAttackLocked(style.phase(),style.combo(),now-style.comboStart()))return;
        if (now < c.ready[id-1]) {
            p.displayClientMessage(Component.literal("Habilidade em recarga (" + ((c.ready[id-1]-now+19)/20) + " s)."),true);
            return;
        }
        if (c.energy < COST[id-1]) {
            p.displayClientMessage(Component.literal("Energia insuficiente ("+c.energy+"/"+COST[id-1]+")."),true);
            return;
        }
        LeapTrajectory.Plan leap = null;
        if (id == 4) {
            if (p.getAbilities().flying || p.isInWater() || p.isInLava()) return;
            leap = LeapTrajectory.aim(p, p.position(), p.getEyePosition(), p.getLookAngle());
            if (leap == null) {
                p.displayClientMessage(Component.literal("Nenhuma superfície alcançável para o salto."),true);
                return;
            }
        }
        boolean quickDash = id == 1 && c.leapStart >= 0 && c.leapLanded < 0
                && now - c.leapStart >= 10;
        c.energy-=COST[id-1]; c.ready[id-1]=now+COOLDOWN[id-1];
        p.displayClientMessage(net.minecraft.network.chat.Component.literal(NAMES[id-1]),true);
        c.preparationTicks = id == 1 && !quickDash ? DASH_PREPARATION_TICKS : 0;
        c.skill=id; c.start=now; c.anchor=p.position(); c.level=p.serverLevel();
        c.hit.clear();c.swing=-1;c.dashed=false;
        c.direction=Vec3.directionFromRotation(0,p.getYRot());
        p.stopUsingItem();
        if(leap!=null) {
            c.leapStart=now;c.leapLanded=-1;c.leapAirborne=!p.onGround();
            c.leapOriginY = p.getY();
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
        return switch(skill){case 1->(right+left+mouth)*1.35;case 2->left+right*.5;case 3->(right+left+mouth)*2;default->0;};
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
            hurtWithSkill(p,c,target,damage,fire);
        }
    }

    private static boolean hurtWithSkill(ServerPlayer player, Combat combat, LivingEntity target, float damage, boolean fire) {
        combat.hit.add(target.getUUID());
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
        float damage = (float) damage(player, 2);
        for (var target : player.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(eye, eye).inflate(radius, radius + 1.25, radius), target -> validTarget(player, target))) {
            AABB box = target.getBoundingBox();
            if (!insideMoonSlash(eye, direction, reach, box) || !visible(player, eye, target)
                    || !hurtWithSkill(player, combat, target, damage, false)) continue;
            target.stopUsingItem();
            STUNS.put(target, new Stun(target.position(), target.level().getGameTime() + 30));
            Vec3 center = box.getCenter();
            Vec3 incoming = center.subtract(eye).multiply(1, 0, 1).normalize();
            if (incoming.lengthSqr() == 0) incoming = horizontal;
            effect(player, 2, center, incoming, target.getId());
        }

        Vec3 end = eye.add(direction.scale(radius));
        effect(player, 9, eye, end, -1);
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
        if(now%PASSIVE_INTERVAL==0 && p.isAlive() && c.energy<MAX_ENERGY){c.energy++;sync(p,c);}
        StyleState style=StyleService.current(p);
        if(style==null || !style.phase().equals("ACTIVE") || !p.isAlive() || c.level!=null&&c.level!=p.level()) {
            if(c.skill!=0){c.skill=0;sync(p,c);}
            c.swing=-1;c.leapStart=-1;
        } else {
            sweepTick(p,c,now);
            int age=(int)(now-c.start);
            if(c.skill==1 && age>=c.preparationTicks && !c.dashed)dash(p,c,6,1);
            if(c.skill==2 && age==3)moonSlash(p,c);
            if(c.skill==3) {
                if(age>=13 && age<43 && (age-13)%10==0) sound(p,net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,.28f, .8f+(age-13)/30f);
                if(age<43){c.direction=Vec3.directionFromRotation(0,p.getYRot());safeMove(p,c.anchor);}
                if(age==43){dash(p,c,10,3);c.anchor=p.position();}
                if(age>43)safeMove(p,c.anchor);
            }
            if(c.leapStart>=0) {
                if (!p.onGround() && Math.abs(p.getY() - c.leapOriginY) > .02) c.leapAirborne = true;
                p.fallDistance=0;
                if(c.leapAirborne && p.onGround() && c.leapLanded<0) {
                    c.leapLanded=now;
                    sound(p,net.minecraft.sounds.SoundEvents.PLAYER_SMALL_FALL,.6f,1.1f);
                    effect(p,8,p.position(),p.position(),p.getId());
                    if(c.skill==4){c.skill=0;sync(p,c);}
                }
                if(c.leapLanded>=0 && (now-c.leapLanded>=10 || !p.onGround()))c.leapStart=-1;
                if(c.leapStart>=0 && now-c.leapStart>160){c.leapStart=-1;if(c.skill==4){c.skill=0;sync(p,c);}}
            }
            
            int duration=switch(c.skill){case 1->c.preparationTicks+25;case 2->12;case 3->103;case 4->180;default->Integer.MAX_VALUE;};
            if(age>=duration){c.skill=0;sync(p,c);}
        }
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
    private static void dash(ServerPlayer p,Combat c,double distance,int kind) {
        c.dashed=true;
        sound(p,net.minecraft.sounds.SoundEvents.TRIDENT_THROW.value(),kind==3 ? .9f : .6f,kind==3 ? .65f : 1.1f);
        if(kind==3) sound(p,net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_KNOCKBACK,.9f,.7f);
        Vec3 from=p.position();
        c.direction=Vec3.directionFromRotation(0,p.getYRot());
        Vec3 requested=c.direction.scale(distance);
        Vec3 allowed=Entity.collideBoundingBox(p,requested,p.getBoundingBox(),p.level(),
                p.level().getEntityCollisions(p,p.getBoundingBox().expandTowards(requested)));
        double x=Math.abs(requested.x)>.0001?allowed.x/requested.x:1;
        double z=Math.abs(requested.z)>.0001?allowed.z/requested.z:1;
        Vec3 destination = from.add(requested.scale(Math.clamp(Math.min(x,z),0,1)));
        Vec3 to = safeMove(p, destination, kind == 1 ? Set.of(RelativeMovement.Y) : Set.of());
        strike(p,c,from.add(0,1,0),to.add(0,1,0),1.2f,(float)damage(p,kind),false);
        effect(p,kind,from,to,p.getId());
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
        c.awakening=Math.min(100,c.awakening+1);
        sync(p,c);
    }
    @SubscribeEvent public static void killed(LivingDeathEvent event) {
        if(event.getEntity() instanceof Monster && event.getSource().getEntity() instanceof ServerPlayer p){
            Combat c=combat(p);c.energy=Math.min(MAX_ENERGY,c.energy+MONSTER_KILL_ENERGY);sync(p,c);
        }
        STUNS.remove(event.getEntity());
        if(event.getEntity() instanceof ServerPlayer p){Combat c=combat(p);c.skill=0;c.fireStart=-1;sync(p,c);}
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        PLAYERS.remove(event.getEntity().getUUID());STUNS.remove(event.getEntity());
    }
    @SubscribeEvent public static void unload(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if(event.getEntity() instanceof LivingEntity living)STUNS.remove(living);
    }
    @SubscribeEvent public static void clone(PlayerEvent.Clone event) {
        event.getEntity().getPersistentData().putInt(ENERGY,event.getOriginal().getPersistentData().getInt(ENERGY));
        event.getEntity().getPersistentData().putLongArray("fightingarts_cooldowns",event.getOriginal().getPersistentData().getLongArray("fightingarts_cooldowns"));
        event.getEntity().getPersistentData().putInt("fightingarts_awakening",event.getOriginal().getPersistentData().getInt("fightingarts_awakening"));
        PLAYERS.remove(event.getEntity().getUUID());
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if(event.getEntity() instanceof ServerPlayer p)sync(p,combat(p));
    }
    @SubscribeEvent public static void tracking(PlayerEvent.StartTracking event) {
        if(event.getEntity() instanceof ServerPlayer watcher && event.getTarget() instanceof ServerPlayer p){
            Combat c=combat(p);
            PacketDistributor.sendToPlayer(watcher,new Status(p.getUUID(),c.energy,c.awakening,c.skill,c.start,c.ready[0],c.ready[1],c.ready[2],c.ready[3],c.preparationTicks));
        }
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        PLAYERS.clear();STUNS.clear();
    }
    private record Stun(Vec3 anchor,long until){}
    private static final class Combat {
        int energy,awakening,skill,combo,preparationTicks;double leapOriginY;long start,swing=-1,fireStart=-1,leapStart=-1,leapLanded=-1;boolean leapAirborne;
        final long[] ready=new long[4];float swingYaw;boolean skillDamage,dashed;
        Vec3 anchor=Vec3.ZERO,direction=Vec3.ZERO,fireFrom=Vec3.ZERO,fireTo=Vec3.ZERO;
        ServerLevel level;
        final Set<UUID> hit=new HashSet<>(),swingHits=new HashSet<>();
        Combat(ServerPlayer p){
            energy=Math.clamp(p.getPersistentData().getInt(ENERGY),0,MAX_ENERGY);
            awakening=Math.clamp(p.getPersistentData().getInt("fightingarts_awakening"),0,100);
            long[] saved=p.getPersistentData().getLongArray("fightingarts_cooldowns");
            System.arraycopy(saved,0,ready,0,Math.min(saved.length,ready.length));
        }
    }
}
