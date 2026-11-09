package fightingarts;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.*;

/** Authoritative Three Sword Style state and combat timing. */
public final class StyleService {
    public static final int DRAW_TICKS = 65;
    public static final int ENTRY_TICKS = DRAW_TICKS + 30;
    public static final int SHEATH_TICKS = 50;
    public static final int COMBO1_WINDOW_TICKS = 40;
    public static final int COMBO_WINDOW_TICKS = 30;
    public static final int COMBO12_LOCK_TICKS = 10;
    public static final int COMBO3_LOCK_TICKS = 20;
    public static final int SHEATH_SLOW_TICKS = 50;
    private static final Map<UUID, MutableState> STATES = new HashMap<>();
    private static final Map<UUID, List<ItemStack>> OFF_SWORDS = new HashMap<>();
    public static final ResourceLocation SHEATH_SLOW = ResourceLocation.fromNamespaceAndPath(FightingArts.MOD_ID, "sheathing_slow");
    private StyleService() {}

    public static void toggle(ServerPlayer player) {
        MutableState state = STATES.get(player.getUUID());
        long now = player.serverLevel().getGameTime();
        if (state == null || state.phase.equals("OFF")) {
            List<ItemStack> swords = findSwords(player);
            if (swords.size() < 3 || !player.isAlive() || player.isSpectator()) return;
            state = new MutableState("DRAWING", now, 0, 0, swords, player.position());
            STATES.put(player.getUUID(), state);
        } else if (state.phase.equals("ACTIVE")) {
            state.phase = "SHEATHING";
            state.phaseStart = now;
            var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.addTransientModifier(new AttributeModifier(SHEATH_SLOW, -0.85, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else return;
        sync(player, state);
    }

    public static void attack(ServerPlayer player) {
        MutableState s = STATES.get(player.getUUID());
        long now = player.serverLevel().getGameTime();
        if (s == null || !s.phase.equals("ACTIVE") || locked(s, now)) return;

        int comboWindow = s.combo == 1 ? COMBO1_WINDOW_TICKS : COMBO_WINDOW_TICKS;
        s.combo = (s.combo == 1 || s.combo == 2) && now - s.comboStart < comboWindow ? s.combo % 3 + 1 : 1;
        s.comboStart = now;
        sync(player, s);
    }

    private static boolean locked(MutableState s, long now) {
        return isAttackLocked(s.phase, s.combo, now - s.comboStart);
    }

    public static boolean isAttackLocked(String phase, int combo, long ticksSinceCombo) {
        if (phase.equals("DRAWING") || phase.equals("SHEATHING")) return true;
        if (!phase.equals("ACTIVE")) return false;
        return switch (combo) {
            case 1, 2 -> ticksSinceCombo < COMBO12_LOCK_TICKS;
            case 3 -> ticksSinceCombo < COMBO3_LOCK_TICKS;
            default -> false;
        };
    }

    public static List<ItemStack> findSwords(Player p) {
        List<ItemStack> found = new ArrayList<>(3);
        // Inventory.items uses slots 0-8 for the hotbar and 9-35 for the main inventory.
        var items = p.getInventory().items;
        for (int slot = 0; slot < items.size() && found.size() < 3; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.is(ItemTags.SWORDS)) found.add(stack.copyWithCount(1));
        }
        ItemStack offhand = p.getOffhandItem();
        if (found.size() < 3 && offhand.is(ItemTags.SWORDS)) found.add(offhand.copyWithCount(1));
        return List.copyOf(found);
    }

    private static void sync(ServerPlayer p, MutableState s) { StyleNetwork.broadcast(p, snapshot(p.getUUID(), s)); }
    private static StyleState snapshot(UUID id, MutableState s) {
        return new StyleState(id, s.phase, s.phaseStart, s.combo, s.comboStart, s.swords);
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
        if (s.phase.equals("ACTIVE")) {
            List<ItemStack> currentSwords = findSwords(p);
            if (currentSwords.size() < 3) {
                STATES.remove(p.getUUID()); removeSlow(p); OFF_SWORDS.put(p.getUUID(), currentSwords);
                StyleNetwork.broadcast(p,new StyleState(p.getUUID(),"OFF",now,0,0,currentSwords)); return;
            }
            if (!sameLists(s.swords, currentSwords)) {
                s.swords = currentSwords;
                sync(p, s);
            }
        }
        if (s.phase.equals("DRAWING") && now - s.phaseStart >= ENTRY_TICKS) {
            List<ItemStack> currentSwords = findSwords(p);
            if (currentSwords.size() < 3) {
                STATES.remove(p.getUUID());
                removeSlow(p);
                OFF_SWORDS.put(p.getUUID(), currentSwords);
                StyleNetwork.broadcast(p, new StyleState(p.getUUID(), "OFF", now, 0, 0, currentSwords));
                return;
            }
            s.swords = currentSwords;
            s.phase = "ACTIVE";
            s.phaseStart = now;
            sync(p,s);
        }
        else if (s.phase.equals("SHEATHING") && now - s.phaseStart >= SHEATH_TICKS) { STATES.remove(p.getUUID()); removeSlow(p); List<ItemStack> swords=findSwords(p); OFF_SWORDS.put(p.getUUID(),swords); StyleNetwork.broadcast(p, new StyleState(p.getUUID(),"OFF",now,0,0,swords)); return; }
        if (s.phase.equals("DRAWING")) {
            p.teleportTo(s.anchor.x, s.anchor.y, s.anchor.z); p.setDeltaMovement(Vec3.ZERO); p.hurtMarked = true;
        }
    }

    @SubscribeEvent public static void attackEvent(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        MutableState s=STATES.get(p.getUUID());
        if (s == null) return;
        if (locked(s,p.serverLevel().getGameTime())) { e.setCanceled(true); return; }
        if (s.phase.equals("ACTIVE")) attack(p);
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
        if(e.getEntity() instanceof ServerPlayer p) { STATES.remove(p.getUUID()); removeSlow(p); StyleNetwork.broadcast(p,new StyleState(p.getUUID(),"OFF",p.serverLevel().getGameTime(),0,0,findSwords(p))); }
    }
    @SubscribeEvent public static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) { if(e.getEntity() instanceof ServerPlayer p) { STATES.remove(p.getUUID()); removeSlow(p); StyleNetwork.broadcast(p,new StyleState(p.getUUID(),"OFF",p.serverLevel().getGameTime(),0,0,List.of())); } }
    private static boolean sameLists(List<ItemStack> a,List<ItemStack> b){ if(a==null||a.size()!=b.size())return false; for(int i=0;i<a.size();i++) if(!ItemStack.matches(a.get(i),b.get(i))) return false; return true; }
    private static void removeSlow(ServerPlayer p){ var speed=p.getAttribute(Attributes.MOVEMENT_SPEED); if(speed!=null)speed.removeModifier(SHEATH_SLOW); }
    private static void syncExisting(ServerPlayer recipient) {
        for (ServerPlayer p : recipient.getServer().getPlayerList().getPlayers()) { MutableState s=STATES.get(p.getUUID()); if(s!=null) StyleNetwork.send(recipient,snapshot(p.getUUID(),s)); }
    }

    private static final class MutableState {
        String phase; long phaseStart, comboStart; int combo; List<ItemStack> swords; final Vec3 anchor;
        MutableState(String phase,long start,int combo,long comboStart,List<ItemStack> swords,Vec3 anchor){this.phase=phase;this.phaseStart=start;this.combo=combo;this.comboStart=comboStart;this.swords=swords;this.anchor=anchor;}
    }
}
