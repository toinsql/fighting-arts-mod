package fightingarts;

import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.UUID;

/** Immutable animation snapshot synchronized from the authoritative server. */
public record StyleState(UUID playerId, String phase, long phaseStart, int combo, long comboStart,
                         List<ItemStack> swords, long fallingAttackStart, long fallingHitStart, boolean entryAirborne,
                         StyleMode mode, long awakeningEndsAt) {
    public StyleState(UUID playerId, String phase, long phaseStart, int combo, long comboStart, List<ItemStack> swords) {
        this(playerId, phase, phaseStart, combo, comboStart, swords, -1, -1, false, StyleMode.NONE);
    }
    public StyleState(UUID playerId, String phase, long phaseStart, int combo, long comboStart, List<ItemStack> swords,
                      long fallingAttackStart, long fallingHitStart, boolean entryAirborne, StyleMode mode) {
        this(playerId, phase, phaseStart, combo, comboStart, swords, fallingAttackStart, fallingHitStart, entryAirborne, mode, -1);
    }

    public float awakeningRemaining(double tick) {
        return awakeningEndsAt < 0 ? 1 : (float)Math.clamp((awakeningEndsAt - tick) / StyleService.AWAKENED_TICKS, 0, 1);
    }
    public StyleState {
        swords = swords.stream().map(ItemStack::copy).toList();
    }
}
