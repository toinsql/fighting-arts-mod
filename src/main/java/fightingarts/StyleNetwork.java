package fightingarts;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;
import java.util.function.Consumer;

public final class StyleNetwork {
    public static Consumer<StyleState> clientReceiver = state -> {};
    private static final ResourceLocation TOGGLE = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,"toggle");
    private static final ResourceLocation ATTACK = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,"attack");
    private static final ResourceLocation STATE = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID,"state");
    private StyleNetwork() {}
    public static void registerPayloads(IEventBus bus) { bus.addListener(StyleNetwork::register); }
    public static void requestToggle() { PacketDistributor.sendToServer(new Toggle()); }
    public static void requestAttack() { PacketDistributor.sendToServer(new Attack()); }
    private static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r=e.registrar("1");
        r.playToServer(Toggle.TYPE,Toggle.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer sp) StyleService.toggle(sp);}));
        r.playToServer(Attack.TYPE,Attack.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer sp) StyleService.attack(sp);}));
        r.playToClient(Snapshot.TYPE,Snapshot.CODEC,(p,c)->p.handle(c));
    }
    public static void broadcast(ServerPlayer p, StyleState state) { PacketDistributor.sendToPlayersTrackingEntityAndSelf(p,new Snapshot(state)); }
    public static void send(ServerPlayer p, StyleState state) { PacketDistributor.sendToPlayer(p,new Snapshot(state)); }
    private record Toggle() implements CustomPacketPayload {
        static final Type<Toggle> TYPE=new Type<>(TOGGLE);
        static final StreamCodec<RegistryFriendlyByteBuf,Toggle> CODEC=StreamCodec.unit(new Toggle());
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private record Attack() implements CustomPacketPayload {
        static final Type<Attack> TYPE=new Type<>(ATTACK);
        static final StreamCodec<RegistryFriendlyByteBuf,Attack> CODEC=StreamCodec.unit(new Attack());
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private record Snapshot(StyleState state) implements CustomPacketPayload {
        static final Type<Snapshot> TYPE=new Type<>(STATE);
        static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=StreamCodec.of((b,p)->{
            b.writeUUID(p.state.playerId()); b.writeUtf(p.state.phase()); b.writeVarLong(p.state.phaseStart());
            b.writeVarInt(p.state.combo()); b.writeVarLong(p.state.comboStart()); b.writeVarInt(p.state.swords().size());
            p.state.swords().forEach(s->net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(b,s));
        },b->{UUID id=b.readUUID();String phase=b.readUtf();long start=b.readVarLong();int combo=b.readVarInt();long cs=b.readVarLong();int n=b.readVarInt();
            java.util.List<net.minecraft.world.item.ItemStack> swords=new java.util.ArrayList<>();for(int i=0;i<n;i++) swords.add(net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(b));
            return new Snapshot(new StyleState(id,phase,start,combo,cs,swords));});
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
        void handle(IPayloadContext c){c.enqueueWork(()->clientReceiver.accept(state));}
    }
}
