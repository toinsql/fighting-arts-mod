package fightingarts;

import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.UUID;

/** Immutable animation snapshot synchronized from the authoritative server. */
public record StyleState(UUID playerId, String phase, long phaseStart, int combo, long comboStart,
                         List<ItemStack> swords) {
    public StyleState {
        swords = swords.stream().map(ItemStack::copy).toList();
    }
}
