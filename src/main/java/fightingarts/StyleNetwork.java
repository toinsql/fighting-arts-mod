package fightingarts;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;
import java.util.function.Consumer;

public final class StyleNetwork {
    public static Consumer<StyleState> clientReceiver = state -> {};
    public static Consumer<DamageNumber> clientDamageReceiver = damage -> {};
    public static Consumer<DashImpulse> clientDashReceiver = impulse -> {};
    public static Consumer<AirJump> clientJumpReceiver = jump -> {};
    public static Consumer<StyleDefense.Pose> clientGuardReceiver = pose -> {};
    public static Consumer<CombatConsumables.Snapshot> clientConsumableReceiver = snapshot -> {};
    private static final ResourceLocation TOGGLE = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,"toggle");
    private static final ResourceLocation ATTACK = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,"attack");
    private static final ResourceLocation STATE = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,"state");
    private StyleNetwork() {}
    public static void registerPayloads(IEventBus bus) { bus.addListener(StyleNetwork::register); }
    public static void requestToggle(StyleMode mode) { PacketDistributor.sendToServer(new Toggle(mode)); }
    public static void requestAttack(boolean fallingOnly) { PacketDistributor.sendToServer(new Attack(fallingOnly)); }
    public static void requestGuard(boolean held) { PacketDistributor.sendToServer(new GuardInput(held)); }
    public static void requestConsumable(boolean held) { PacketDistributor.sendToServer(new ConsumableInput(held)); }
    private static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r=e.registrar("13");
        r.playToServer(ConsumableInput.TYPE, ConsumableInput.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) CombatConsumables.setHeld(player, packet.held());
        }));
        r.playToClient(ConsumableSnapshot.TYPE, ConsumableSnapshot.CODEC,
                (packet, context) -> context.enqueueWork(() -> clientConsumableReceiver.accept(packet.snapshot())));
        r.playToServer(GuardInput.TYPE, GuardInput.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) StyleDefense.setHeld(player, packet.held());
        }));
        r.playToClient(GuardSnapshot.TYPE, GuardSnapshot.CODEC,
                (packet, context) -> context.enqueueWork(() -> clientGuardReceiver.accept(packet.pose())));
        r.playToServer(Toggle.TYPE,Toggle.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer sp) StyleService.toggle(sp,p.mode());}));
        r.playToServer(Attack.TYPE,Attack.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer sp) StyleService.attack(sp,p.fallingOnly());}));
        r.playToClient(AirJump.TYPE,AirJump.CODEC,(p,c)->c.enqueueWork(()->clientJumpReceiver.accept(p)));
        r.playToClient(Snapshot.TYPE,Snapshot.CODEC,(p,c)->p.handle(c));
        r.playToClient(DamageNumber.TYPE, DamageNumber.CODEC,
                (p,c)->c.enqueueWork(()->clientDamageReceiver.accept(p)));
        r.playToClient(DashImpulse.TYPE, DashImpulse.CODEC,
                (p,c)->c.enqueueWork(()->clientDashReceiver.accept(p)));
    }
    public static void broadcast(ServerPlayer p, StyleState state) { PacketDistributor.sendToPlayersTrackingEntityAndSelf(p,new Snapshot(state)); }
    public static void send(ServerPlayer p, StyleState state) { PacketDistributor.sendToPlayer(p,new Snapshot(state)); }
    static void broadcastGuard(ServerPlayer player, StyleDefense.Pose pose) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new GuardSnapshot(pose));
    }
    static void sendGuard(ServerPlayer watcher, StyleDefense.Pose pose) {
        PacketDistributor.sendToPlayer(watcher, new GuardSnapshot(pose));
    }

    static void broadcastConsumable(ServerPlayer player, CombatConsumables.Snapshot snapshot) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new ConsumableSnapshot(snapshot));
    }

    static void sendConsumable(ServerPlayer watcher, CombatConsumables.Snapshot snapshot) {
        PacketDistributor.sendToPlayer(watcher, new ConsumableSnapshot(snapshot));
    }

    private record ConsumableInput(boolean held) implements CustomPacketPayload {
        static final Type<ConsumableInput> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID, "consumable_input"));
        static final StreamCodec<RegistryFriendlyByteBuf, ConsumableInput> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeBoolean(packet.held), buf -> new ConsumableInput(buf.readBoolean()));

        @Override public Type<ConsumableInput> type() { return TYPE; }
    }

    private record ConsumableSnapshot(CombatConsumables.Snapshot snapshot) implements CustomPacketPayload {
        static final Type<ConsumableSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID, "consumable_snapshot"));
        static final StreamCodec<RegistryFriendlyByteBuf, ConsumableSnapshot> CODEC = StreamCodec.of((buf, packet) -> {
            buf.writeUUID(packet.snapshot.player());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.snapshot.stack());
            buf.writeVarLong(packet.snapshot.tick());
            buf.writeVarInt(packet.snapshot.duration());
        }, buf -> new ConsumableSnapshot(new CombatConsumables.Snapshot(buf.readUUID(),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readVarLong(), buf.readVarInt())));

        @Override public Type<ConsumableSnapshot> type() { return TYPE; }
    }

    private record GuardInput(boolean held) implements CustomPacketPayload {
        static final Type<GuardInput> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID, "guard_input"));
        static final StreamCodec<RegistryFriendlyByteBuf, GuardInput> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeBoolean(packet.held), buf -> new GuardInput(buf.readBoolean()));
        @Override public Type<GuardInput> type() { return TYPE; }
    }

    private record GuardSnapshot(StyleDefense.Pose pose) implements CustomPacketPayload {
        static final Type<GuardSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID, "guard_snapshot"));
        static final StreamCodec<RegistryFriendlyByteBuf, GuardSnapshot> CODEC = StreamCodec.of((buf, packet) -> {
            buf.writeUUID(packet.pose.player());
            buf.writeLong(packet.pose.raisedAt());
            buf.writeLong(packet.pose.releasedAt());
        }, buf -> new GuardSnapshot(new StyleDefense.Pose(buf.readUUID(), buf.readLong(), buf.readLong())));
        @Override public Type<GuardSnapshot> type() { return TYPE; }
    }
    static void broadcastJump(ServerPlayer player,boolean rightLeading){
        PacketDistributor.sendToPlayersTrackingEntity(player,new AirJump(player.getUUID(),player.level().getGameTime(),
                rightLeading,(float)player.getDeltaMovement().y));
    }

    public record AirJump(UUID player,long tick,boolean rightLeading,float velocity) implements CustomPacketPayload {
        static final Type<AirJump> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,"air_jump"));
        static final StreamCodec<RegistryFriendlyByteBuf,AirJump> CODEC=StreamCodec.of((b,p)->{
            b.writeUUID(p.player);b.writeLong(p.tick);b.writeBoolean(p.rightLeading);b.writeFloat(p.velocity);
        },b->new AirJump(b.readUUID(),b.readLong(),b.readBoolean(),b.readFloat()));
        @Override public Type<AirJump> type(){return TYPE;}
    }

    public static void sendDamageNumber(ServerPlayer attacker, Vec3 position, float amount) {
        PacketDistributor.sendToPlayer(attacker, new DamageNumber(position, amount));
    }

    public static void sendDashImpulse(ServerPlayer player, double x, double z) {
        PacketDistributor.sendToPlayer(player, new DashImpulse(x, z));
    }

    public record DashImpulse(double x, double z) implements CustomPacketPayload {
        static final Type<DashImpulse> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID, "dash_impulse"));
        static final StreamCodec<RegistryFriendlyByteBuf, DashImpulse> CODEC = StreamCodec.of((buf, impulse) -> {
            buf.writeDouble(impulse.x);
            buf.writeDouble(impulse.z);
        }, buf -> new DashImpulse(buf.readDouble(), buf.readDouble()));

        @Override public Type<DashImpulse> type() { return TYPE; }
    }

    public record DamageNumber(Vec3 position, float amount) implements CustomPacketPayload {
        static final Type<DamageNumber> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID, "damage_number"));
        static final StreamCodec<RegistryFriendlyByteBuf, DamageNumber> CODEC = StreamCodec.of((buf, number) -> {
            buf.writeDouble(number.position.x);
            buf.writeDouble(number.position.y);
            buf.writeDouble(number.position.z);
            buf.writeFloat(number.amount);
        }, buf -> new DamageNumber(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readFloat()));

        @Override public Type<DamageNumber> type() { return TYPE; }
    }

    private record Toggle(StyleMode mode) implements CustomPacketPayload {
        static final Type<Toggle> TYPE=new Type<>(TOGGLE);
        static final StreamCodec<RegistryFriendlyByteBuf,Toggle> CODEC=StreamCodec.of(
                (buf, toggle) -> buf.writeEnum(toggle.mode()), buf -> new Toggle(buf.readEnum(StyleMode.class)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private record Attack(boolean fallingOnly) implements CustomPacketPayload {
        static final Type<Attack> TYPE=new Type<>(ATTACK);
        static final StreamCodec<RegistryFriendlyByteBuf,Attack> CODEC=StreamCodec.of(
                (b,p)->b.writeBoolean(p.fallingOnly()), b->new Attack(b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private record Snapshot(StyleState state) implements CustomPacketPayload {
        static final Type<Snapshot> TYPE=new Type<>(STATE);
        static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=StreamCodec.of((b,p)->{
            b.writeUUID(p.state.playerId()); b.writeUtf(p.state.phase()); b.writeVarLong(p.state.phaseStart());
            b.writeVarInt(p.state.combo()); b.writeVarLong(p.state.comboStart()); b.writeVarInt(p.state.swords().size());
            p.state.swords().forEach(s->net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(b,s));
            b.writeLong(p.state.fallingAttackStart());
            b.writeLong(p.state.fallingHitStart());
            b.writeBoolean(p.state.entryAirborne());
            b.writeEnum(p.state.mode());
            b.writeLong(p.state.awakeningEndsAt());
        },b->{UUID id=b.readUUID();String phase=b.readUtf();long start=b.readVarLong();int combo=b.readVarInt();long cs=b.readVarLong();int n=b.readVarInt();
            java.util.List<net.minecraft.world.item.ItemStack> swords=new java.util.ArrayList<>();for(int i=0;i<n;i++) swords.add(net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(b));
            return new Snapshot(new StyleState(id,phase,start,combo,cs,swords,b.readLong(),b.readLong(),b.readBoolean(),b.readEnum(StyleMode.class),b.readLong()));});
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
        void handle(IPayloadContext c){c.enqueueWork(()->clientReceiver.accept(state));}
    }
}
