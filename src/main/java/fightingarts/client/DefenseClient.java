package fightingarts.client;

import fightingarts.SantoryuSkills;
import fightingarts.StyleDefense;
import fightingarts.StyleNetwork;
import fightingarts.StyleService;
import fightingarts.StyleState;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;

public final class DefenseClient {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Map<UUID, StyleDefense.Pose> GUARDS = new HashMap<>();
    private static ClientLevel level;
    private static boolean sentHeld;

    private DefenseClient() {}

    static void install() {
        StyleNetwork.clientGuardReceiver = pose -> {
            updateWorld();
            GUARDS.put(pose.player(), pose);
        };
    }

    private static void updateWorld() {
        if (level == MC.level) return;
        level = MC.level;
        GUARDS.clear();
        sentHeld = false;
    }

    static void tick() {
        updateWorld();
        if (level == null || MC.player == null || MC.getConnection() == null) return;
        StyleState state = StyleClient.local();
        long now = level.getGameTime();
        boolean held = MC.screen == null && !BlindSight.active() && MC.options.keyUse.isDown() && state != null && state.phase().equals("ACTIVE")
                && MC.player.isAlive() && !MC.player.isSpectator() && MC.player.getOffhandItem().isEmpty()
                && !SantoryuSkills.stunned(MC.player) && (SkillHud.local() == null || SkillHud.local().skill() == 0)
                && !StyleService.isAttackLocked(state.phase(), state.combo(), now - state.comboStart());
        if (held != sentHeld) {
            sentHeld = held;
            StyleNetwork.requestGuard(held);
        }
        GUARDS.values().removeIf(guard -> !guard.held() && now - guard.releasedAt() >= StyleDefense.RAISE_TICKS);
        if (StyleClient.attackMode() && MC.player.isSwimming()) MC.player.setSwimming(false);
    }

    static void stateChanged(StyleState state) {
        if (!state.phase().equals("ACTIVE")) GUARDS.remove(state.playerId());
    }

    static boolean localGuarding() {
        if (MC.player == null || !StyleClient.attackMode()) return false;
        var pose = GUARDS.get(MC.player.getUUID());
        return sentHeld || pose != null && pose.held();
    }

    static StyleDefense.Pose pose(AbstractClientPlayer player, StyleState style) {
        return style.phase().equals("ACTIVE") ? GUARDS.get(player.getUUID()) : null;
    }
}
