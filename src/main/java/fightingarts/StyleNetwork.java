package fightingarts;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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
    private static final ResourceLocation TOGGLE = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,"toggle");
    private static final ResourceLocation ATTACK = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,"attack");
    private static final ResourceLocation STATE = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,"state");
    private StyleNetwork() {}
    public static void registerPayloads(IEventBus bus) { bus.addListener(StyleNetwork::register); }
    public static void requestToggle(StyleMode mode) { PacketDistributor.sendToServer(new Toggle(mode)); }
    public static void requestAttack(boolean fallingOnly) { PacketDistributor.sendToServer(new Attack(fallingOnly)); }
    private static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r=e.registrar("8");
        r.playToServer(Toggle.TYPE,Toggle.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer sp) StyleService.toggle(sp,p.mode());}));
        r.playToServer(Attack.TYPE,Attack.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer sp) StyleService.attack(sp,p.fallingOnly());}));
        r.playToClient(Snapshot.TYPE,Snapshot.CODEC,(p,c)->p.handle(c));
        r.playToClient(DamageNumber.TYPE, DamageNumber.CODEC,
                (p,c)->c.enqueueWork(()->clientDamageReceiver.accept(p)));
        r.playToClient(DashImpulse.TYPE, DashImpulse.CODEC,
                (p,c)->c.enqueueWork(()->clientDashReceiver.accept(p)));
    }
    public static void broadcast(ServerPlayer p, StyleState state) { PacketDistributor.sendToPlayersTrackingEntityAndSelf(p,new Snapshot(state)); }
    public static void send(ServerPlayer p, StyleState state) { PacketDistributor.sendToPlayer(p,new Snapshot(state)); }
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
        },b->{UUID id=b.readUUID();String phase=b.readUtf();long start=b.readVarLong();int combo=b.readVarInt();long cs=b.readVarLong();int n=b.readVarInt();
            java.util.List<net.minecraft.world.item.ItemStack> swords=new java.util.ArrayList<>();for(int i=0;i<n;i++) swords.add(net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(b));
            return new Snapshot(new StyleState(id,phase,start,combo,cs,swords,b.readLong(),b.readLong(),b.readBoolean(),b.readEnum(StyleMode.class)));});
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
        void handle(IPayloadContext c){c.enqueueWork(()->clientReceiver.accept(state));}
    }
}
