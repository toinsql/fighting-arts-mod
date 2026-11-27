package fightingarts;

import fightingarts.client.GeometryCheck;
import fightingarts.client.StyleClient;
import java.io.File;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

final class CombatContinuityCheck {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final String HITS = "fightingarts_awakening_hits";
    private static final String PERCENT = "fightingarts_awakening";
    private static final String ENERGY = "fightingarts_energy";
    private static final String SAVED_TIME = "fightingarts_awakened_ticks";
    private static volatile int stage;
    private static int ticks, saved;
    private static long resumeAt, nearingEnd;
    private static boolean capturedAwakenedHud;

    static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> client());
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> {
            if (event.getEntity() instanceof ServerPlayer player) server(player);
        });
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> {
            StyleState style = StyleClient.local();
            if (capturedAwakenedHud || stage != 5 || !active(style, StyleMode.AWAKENED)
                    || MC.level.getGameTime() - style.phaseStart() < 12) return;
            capturedAwakenedHud = true;
            event.getGuiGraphics().flush();
            Screenshot.grab(new File("."), "awakened-duration-hud.png", MC.getMainRenderTarget(), text -> System.out.println(text.getString()));
        });
    }

    private static void client() {
        if (MC.player == null || MC.getSingleplayerServer() == null) return;
        MC.options.pauseOnLostFocus = false;
        if (MC.screen != null) MC.setScreen(null);
        require(++ticks < 620, "timed out at stage " + stage);
        StyleState style = StyleClient.local();
        if (stage == 1 && style != null && style.phase().equals("ACTIVE") && MC.player.onGround()) {
            MC.options.setCameraType(CameraType.FIRST_PERSON);
            GeometryCheck.checkFirstPersonAttacks(MC.level);
            StyleNetwork.requestAttack(false);
            stage = 2;
        }
        if (stage == 2 && style != null && style.combo() == 1 && GeometryCheck.liveFirstPersonStrike(MC.player)) {
            stage = 3;
        }
        if (stage == 8 && style != null && style.mode() == StyleMode.COMMON) {
            require(capturedAwakenedHud, "awakened duration HUD rendered");
            System.out.println("FA-REVIEW PASS confirmed first-person attacks, 65 hits, attack-mode persistence, charge drain, energy and dragon cost");
            stage = 9;
            MC.stop();
        }
    }

    private static void server(ServerPlayer p) {
        long now = p.level().getGameTime();
        StyleState style = StyleService.current(p);
        if (stage == 0) {
            prepare(p);
            stage = 1;
        } else if (stage == 3 && style != null && now - style.comboStart() >= StyleService.COMBO12_LOCK_TICKS) {
            restore(p, StyleMode.SIMPLE);
            StyleService.toggle(p, StyleMode.COMMON);
            stage = 4;
        } else if (stage == 4 && active(style, StyleMode.COMMON) && p.onGround()) {
            restore(p, StyleMode.COMMON);
            var cow = EntityType.COW.create(p.level());
            cow.setNoAi(true);
            cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
            cow.setHealth(200);
            cow.setPos(p.getX() + 3, p.getY(), p.getZ());
            p.serverLevel().addFreshEntity(cow);
            for (int hit = 0; hit < 64; hit++) {
                cow.invulnerableTime = 0;
                require(cow.hurt(p.damageSources().playerAttack(p), 1), "damage is credited to the caster");
            }
            require(p.getPersistentData().getInt(HITS) == 64 && p.getPersistentData().getInt(PERCENT) == 98,
                    "64 hits leave the bar incomplete");
            StyleService.toggle(p, StyleMode.AWAKENED);
            require(active(StyleService.current(p), StyleMode.COMMON), "64 hits cannot awaken");
            cow.invulnerableTime = 0;
            require(cow.hurt(p.damageSources().playerAttack(p), 1), "65th hit lands");
            require(p.getPersistentData().getInt(PERCENT) == 100, "65th hit fills the bar");
            cow.discard();
            StyleService.toggle(p, StyleMode.AWAKENED);
            stage = 5;
        } else if (stage == 5 && active(style, StyleMode.AWAKENED) && now - style.phaseStart() >= 35) {
            require(style.awakeningEndsAt() - style.phaseStart() == 6000, "awakened duration starts at five minutes after the entry");
            require(style.awakeningRemaining(style.phaseStart()) == 1
                    && style.awakeningRemaining(style.phaseStart() + 3000) == .5f, "the active bar drains linearly");
            StyleService.logout(new PlayerEvent.PlayerLoggedOutEvent(p));
            saved = p.getPersistentData().getInt(SAVED_TIME);
            require(saved == style.awakeningEndsAt() - now && saved > 0, "logout saves the remaining time");
            resumeAt = now + 25;
            stage = 6;
        } else if (stage == 6 && now >= resumeAt) {
            StyleService.login(new PlayerEvent.PlayerLoggedInEvent(p));
            var resumed = StyleService.current(p);
            require(active(resumed, StyleMode.AWAKENED) && resumed.awakeningEndsAt() == now + saved,
                    "awakening resumes with the same time after offline ticks");
            StyleService.logout(new PlayerEvent.PlayerLoggedOutEvent(p));
            p.getPersistentData().putInt(SAVED_TIME, 21);
            StyleService.login(new PlayerEvent.PlayerLoggedInEvent(p));
            setEnergy(p, 124);
            useDragon(p);
            require(p.getPersistentData().getInt(ENERGY) == 124, "124 energy does not cast the dragon");
            setEnergy(p, 125);
            useDragon(p);
            require(p.getPersistentData().getInt(ENERGY) == 0, "the dragon uses exactly 125 energy");
            nearingEnd = now;
            stage = 7;
        } else if (stage == 7 && now == nearingEnd + 20) {
            require(p.getPersistentData().getInt(ENERGY) == 3 && active(style, StyleMode.AWAKENED),
                    "awakened regeneration provides exactly three energy in one second");
        } else if (stage == 7 && now >= nearingEnd + 21) {
            require(active(style, StyleMode.COMMON) && style.awakeningEndsAt() == -1, "expiration returns to common attack mode");
            require(p.getPersistentData().getInt(PERCENT) == 0, "the bar starts charging again from zero");
            stage = 8;
        }
    }

    private static void prepare(ServerPlayer p) {
        p.setGameMode(GameType.SURVIVAL);
        for (int x = 215; x < 226; x++) for (int z = -296; z < -285; z++) {
            p.level().setBlock(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 100; y < 106; y++) p.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        p.connection.teleport(220.5, 100, -290.5, 0, 0);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        for (int sword = 0; sword < 3; sword++) StyleWeapons.of(p).setItem(sword, new ItemStack(Items.IRON_SWORD));
        p.getPersistentData().remove(HITS);
        p.getPersistentData().putInt(PERCENT, 52);
        setEnergy(p, 160);
        require(p.getPersistentData().getInt(HITS) == 34 && p.getPersistentData().getInt(PERCENT) == 52,
                "existing saved percentage migrates to equivalent hits");
        p.getPersistentData().putInt(HITS, 0);
        setEnergy(p, 160);
        StyleService.toggle(p, StyleMode.SIMPLE);
    }

    private static void setEnergy(ServerPlayer p, int energy) {
        SantoryuSkills.logout(new PlayerEvent.PlayerLoggedOutEvent(p));
        p.getPersistentData().putInt(ENERGY, energy);
        SantoryuSkills.login(new PlayerEvent.PlayerLoggedInEvent(p));
    }

    private static void useDragon(ServerPlayer p) {
        try {
            var use = SantoryuSkills.class.getDeclaredMethod("use", ServerPlayer.class, int.class, float.class);
            use.setAccessible(true);
            use.invoke(null, p, 7, 0f);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void restore(ServerPlayer p, StyleMode mode) {
        StyleService.logout(new PlayerEvent.PlayerLoggedOutEvent(p));
        StyleService.login(new PlayerEvent.PlayerLoggedInEvent(p));
        require(active(StyleService.current(p), mode), "the player rejoins in " + mode);
    }

    private static boolean active(StyleState style, StyleMode mode) {
        return style != null && style.mode() == mode && style.phase().equals("ACTIVE");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("FA-CONTINUITY FAIL " + message);
    }
}
