# Runtime checks (Minecraft 1.21.1 / NeoForge)

Run from the project root with Java 21 and the existing Gradle dependencies/assets installed.

```powershell
# Common mode, leap, cuts, stun, energy, cooldown and input regressions.
./tools/testing/run-client-check.ps1 -WorldPath './run/saves/New World'

# Awakening gates, damage, camera timing, cancellation and visual captures.
./tools/testing/run-client-check.ps1 -WorldPath './run/saves/New World' -Fixture AwakeningCheck

# Dedicated-server class loading and startup/shutdown.
./tools/testing/run-server-check.ps1
```

Client checks copy the supplied world into a new temporary directory before editing terrain or player data.
The test harness compiles separately and is never included in the mod JAR. Each run prints its temporary
directory containing `check-output.log`, the copied world and `screenshots/`. Success requires `FA-REVIEW PASS`.

`AwakeningCheck` captures the live cinematic, then renders a fixed client-side actor from eight viewpoints
(45-degree intervals) at six effect stages. These 48 observer captures exercise the actual renderer,
including the overhead cloud, close noncylindrical motes, front opening, eruption, clearing,
residual soot and the floor after soot expires. Four extra frames inspect a whole strike, its motion,
and the abrupt half-second cutoff. A close view checks the hand aura.
The player/server checks use the real integrated server, synchronized snapshots and awakening request packet.
The clear center of the first-person screenshot is checked for body-lightning projection artifacts.
Storm checks also verify the synchronized strike schedule, caster exclusion zone, fifteen-second soot
retention and projection onto slab/stair tops without bridging a hole. Storms and marks are visual;
the existing awakening shockwave owns damage, and terrain is preserved.

For manual testing with cheats/operator permission: `/fightingarts debug awakening` fills the awakening bar.
Enter common mode, stand on the ground and press **V**. `/fightingarts debug reset` still restores energy
and clears skill cooldowns. The provisional awakening impact deals 16 damage at the center, decreasing
linearly to 2 at 20 blocks, once per target. It respects PvP and teams and does not modify terrain.
