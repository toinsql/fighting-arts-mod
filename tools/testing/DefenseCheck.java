package fightingarts;

import fightingarts.client.StyleClient;
import com.mojang.authlib.GameProfile;
import java.io.File;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Sends real use-key changes and verifies the defense on the integrated server. */
final class DefenseCheck {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Vec3 ARENA = new Vec3(220.5, 100, -290.5);
    private static volatile int stage;
    private static int timeout, releaseFrame, parryFrame;
    private static String capture;
    private static long parryTick = -1, releasedTick = -1;
    private static Zombie front, back;
    private static long playerParry = -1;
    private static ServerPlayer attacker;
    private static boolean checkedRecovery, thirdCharge, emptyCharges, commonEnergyTest;

    static void install() {
        var statusReceiver = StyleNetwork.clientGuardReceiver;
        StyleNetwork.clientGuardReceiver = pose -> {
            statusReceiver.accept(pose);
            if (MC.player != null && pose.player().equals(MC.player.getUUID())) {
                if (pose.held() && stage == 2) capture = "defense-first-person-raise";
                else if (!pose.held() && stage == 4) releaseFrame = 2;
            }
        };
        var effectReceiver = SantoryuSkills.clientEffect;
        SantoryuSkills.clientEffect = effect -> {
            effectReceiver.accept(effect);
            if (effect.kind() == SantoryuSkills.PARRY_EFFECT) parryFrame = 2;
        };
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> client());
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> {
            if (event.getEntity() instanceof ServerPlayer player) checkCombat(player);
        });
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> {
            if (capture == null) return;
            String name = capture;
            capture = null;
            event.getGuiGraphics().flush();
            Screenshot.grab(new File("."), name + ".png", MC.getMainRenderTarget(), message -> System.out.println(message.getString()));
        });
    }

    private static void client() {
        if (MC.player == null || MC.getSingleplayerServer() == null) return;
        MC.options.pauseOnLostFocus = false;
        if (MC.screen != null) MC.setScreen(null);
        require(++timeout < 1100, "fixture timeout at stage " + stage);
        var style = StyleClient.local();
        if (parryFrame > 0 && --parryFrame == 0) capture = MC.options.getCameraType().isFirstPerson()
                ? "defense-first-person-parry" : "defense-third-person-parry";
        if (releaseFrame > 0 && --releaseFrame == 0) capture = "defense-first-person-release";
        if (stage == 2 || stage == 7 || stage == 11) MC.options.keyUse.setDown(true);
        switch (stage) {
            case 0 -> {
                MC.options.renderDistance().set(6);
                MC.options.simulationDistance().set(5);
                stage = 1;
                server(DefenseCheck::prepare);
            }
            case 1 -> {
                // Vanilla rejects incoming hits during the login protection window.
                if (active(style, StyleMode.SIMPLE) && age(style) >= 60) {
                    MC.player.setSwimming(true);
                    require(!MC.player.isSwimming(), "client cannot predict swimming in simple mode");
                    MC.options.keyUse.setDown(true);
                    stage = 2;
                }
            }
            case 3 -> {
                capture = "defense-first-person-loop";
                MC.options.keyUse.setDown(false);
                stage = 4;
            }
            case 5 -> { stage = 6; server(p -> StyleService.toggle(p, StyleMode.COMMON)); }
            case 6 -> {
                if (active(style, StyleMode.COMMON) && age(style) > 5) {
                    MC.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                    MC.player.setXRot(-35);
                    MC.options.keyUse.setDown(true);
                    stage = 7;
                }
            }
            case 8 -> {
                fightingarts.client.GeometryCheck.checkGuardMouth();
                capture = "defense-third-person-pitch";
                MC.options.keyUse.setDown(false);
                stage = 9;
            }
            case 10 -> {
                if (active(style, StyleMode.AWAKENED) && age(style) > 5) {
                    MC.options.keyUse.setDown(true);
                    stage = 11;
                }
            }
            case 12 -> {
                capture = "defense-awakened";
                MC.options.keyUse.setDown(false);
                stage = 13;
            }
            case 14 -> {
                if (style != null && style.phase().equals("OFF")) {
                    MC.player.setSwimming(true);
                    require(MC.player.isSwimming(), "client can swim after sheathing");
                    MC.player.setSwimming(false);
                    stage = 15;
                }
            }
            case 16 -> { stage = 17; System.out.println("FA-REVIEW PASS"); MC.stop(); }
        }
    }

    private static void checkCombat(ServerPlayer p) {
        long now = p.level().getGameTime();
        if (stage == 2 && StyleDefense.guarding(p)) {
            if (parryTick < 0) {
                parryTick = now;
                p.setSwimming(true);
                require(!p.isSwimming(), "server cannot start swimming in simple mode");
                setEnergy(p, 0);
                float health = p.getHealth();
                require(!front.doHurtTarget(p) && p.getHealth() == health, "perfect guard cancels mob melee");
                require(energy(p) == 0 && charges(p)[0] == now + 300, "simple parry spends one charge at zero energy");
                require(StyleDefense.disarmed(front) && !SantoryuSkills.stunned(front), "parry disables attacks only");
                require(!front.doHurtTarget(back), "parried mob cannot attack another target");
            }
            if (now == parryTick + 19) require(StyleDefense.disarmed(front), "disarm still active after 19 ticks");
            if (now >= parryTick + 20 && !checkedRecovery) {
                checkedRecovery = true;
                require(!StyleDefense.disarmed(front) && front.doHurtTarget(back), "attacker recovers after 20 ticks");
                require(energy(p) == 0, "energy did not passively regenerate while guarding");
                float health = p.getHealth();
                require(!front.doHurtTarget(p) && p.getHealth() == health, "held guard cancels frontal hit");
                require(charges(p)[1] == now + 300 && !StyleDefense.disarmed(front), "late block spends next charge without parrying");
                require(back.doHurtTarget(p) && charges(p)[2] == 0, "strike from behind bypasses guard");
                p.invulnerableTime = 0;
                require(p.hurt(p.damageSources().fall(), 1) && charges(p)[2] == 0, "fall damage bypasses guard");
            }
            if (now >= parryTick + 28 && !thirdCharge) {
                thirdCharge = true;
                require(!front.doHurtTarget(p) && charges(p)[2] == now + 300, "third simple charge has its own 15-second timer");
            }
            if (now == parryTick + 42 || now == parryTick + 56) {
                int charge = now == parryTick + 42 ? 3 : 4;
                require(!front.doHurtTarget(p) && charges(p)[charge] == now + 300,
                        "additional simple charge blocks and starts its own timer");
            }
            if (now >= parryTick + 70 && !emptyCharges) {
                emptyCharges = true;
                p.invulnerableTime = 0;
                require(front.doHurtTarget(p), "sixth simple attack goes through with no charges");
            }
            if (now >= parryTick + 300) {
                long[] before = charges(p);
                require(before[0] <= now && before[1] > now && before[2] > now, "charges return independently after 15 seconds");
                require(!front.doHurtTarget(p) && charges(p)[0] == now + 300, "returned charge blocks again");
                stage = 3;
            }
        }
        if (stage == 4 && !StyleDefense.guarding(p) && energy(p) > 0) {
            reset(p);
            p.setHealth(p.getMaxHealth());
            System.out.println("FA-DEFENSE SIMPLE PASS RMB, parry, five independent charges, recovery, rear and fall damage");
            stage = 5;
        }
        if (stage == 7 && StyleDefense.guarding(p)) {
            if (playerParry < 0) {
                playerParry = now;
                float health = p.getHealth();
                require(!p.hurt(p.damageSources().playerAttack(attacker), 5) && p.getHealth() == health,
                        "perfect guard cancels player damage in common mode");
                require(StyleDefense.disarmed(attacker) && energy(p) == 165, "player is parried and energy is spent");
                health = front.getHealth();
                attacker.attack(front);
                require(front.getHealth() == health, "parried player cannot attack another target");
                p.setSwimming(true);
                require(!p.isSwimming(), "common mode prevents swimming");
            }
            if (now >= playerParry + 20 && !commonEnergyTest) {
                commonEnergyTest = true;
                float health = front.getHealth();
                attacker.attack(front);
                require(!StyleDefense.disarmed(attacker) && front.getHealth() < health, "player recovers attacks after one second");
                require(energy(p) == 165, "no passive recovery during common guard");
                setEnergy(p, 34);
                p.invulnerableTime = 0;
                require(front.doHurtTarget(p) && energy(p) == 34, "common guard with insufficient energy does not block");
            }
            if (now >= playerParry + 42) {
                require(energy(p) == 34, "passive regeneration stays off even when the held guard cannot pay");
                stage = 8;
            }
        }
        if (stage == 9 && !StyleDefense.guarding(p) && energy(p) > 34) {
            if (releasedTick < 0) releasedTick = now;
            if (now - releasedTick < 10 || !p.onGround()) return;
            p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(2), "fightingarts debug awakening");
            StyleService.toggle(p, StyleMode.AWAKENED);
            if (StyleService.current(p).mode() == StyleMode.AWAKENED) stage = 10;
        }
        if (stage == 11 && StyleDefense.guarding(p)) {
            reset(p);
            require(!front.doHurtTarget(p) && energy(p) == 165, "awakened mode blocks and spends energy");
            p.setSwimming(true);
            require(!p.isSwimming(), "awakened mode prevents swimming");
            stage = 12;
        }
        if (stage == 13 && !StyleDefense.guarding(p)) {
            StyleService.toggle(p, StyleMode.COMMON);
            stage = 14;
        }
        if (stage == 15) {
            p.setSwimming(true);
            require(p.isSwimming(), "server can swim after leaving attack mode");
            p.setSwimming(false);
            System.out.println("FA-DEFENSE MODES PASS all three modes block and disable swimming, exit restores swimming");
            stage = 16;
        }
    }

    private static void prepare(ServerPlayer p) {
        p.setGameMode(GameType.SURVIVAL);
        var level = p.serverLevel();
        level.setDayTime(6000);
        for (int x = 204; x <= 239; x++) for (int z = -307; z <= -271; z++) {
            level.setBlock(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 100; y <= 110; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        level.getEntitiesOfClass(Zombie.class, p.getBoundingBox().move(ARENA.subtract(p.position())).inflate(15))
                .forEach(Zombie::discard);
        p.connection.teleport(ARENA.x, ARENA.y, ARENA.z, 0, 0);
        p.getInventory().selected = 8;
        p.getInventory().setItem(8, ItemStack.EMPTY);
        p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
        p.setHealth(p.getMaxHealth());
        for (int i = 0; i < 3; i++) StyleWeapons.of(p).setItem(i, new ItemStack(Items.IRON_SWORD));
        front = spawn(p, 1.8);
        back = spawn(p, -1.8);
        attacker = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "GuardAttacker"));
        attacker.setPos(ARENA.add(0, 0, 2.6));
        attacker.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        reset(p);
        StyleService.toggle(p, StyleMode.SIMPLE);
        MC.execute(() -> MC.options.setCameraType(CameraType.FIRST_PERSON));
    }

    private static Zombie spawn(ServerPlayer p, double offset) {
        Zombie zombie = EntityType.ZOMBIE.create(p.level());
        zombie.setNoAi(true);
        zombie.setPos(ARENA.add(0, 0, offset));
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        p.serverLevel().addFreshEntity(zombie);
        return zombie;
    }

    private static boolean active(StyleState style, StyleMode mode) {
        return style != null && style.mode() == mode && style.phase().equals("ACTIVE");
    }
    private static long age(StyleState style) { return MC.level.getGameTime() - style.phaseStart(); }
    private static int energy(ServerPlayer p) { return p.getPersistentData().getInt("fightingarts_energy"); }
    private static long[] charges(ServerPlayer p) { return p.getPersistentData().getLongArray("fightingarts_guard_charges"); }
    private static void reset(ServerPlayer p) {
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(2), "fightingarts debug reset");
    }
    private static void setEnergy(ServerPlayer p, int value) {
        try {
            var getter = SantoryuSkills.class.getDeclaredMethod("combat", ServerPlayer.class);
            getter.setAccessible(true);
            Object state = getter.invoke(null, p);
            var field = state.getClass().getDeclaredField("energy");
            field.setAccessible(true);
            field.setInt(state, value);
            var sync = SantoryuSkills.class.getDeclaredMethod("sync", ServerPlayer.class, state.getClass());
            sync.setAccessible(true);
            sync.invoke(null, p, state);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("FA-DEFENSE FAIL " + message);
    }
    private static void server(java.util.function.Consumer<ServerPlayer> action) {
        var server = MC.getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayers().getFirst()));
    }
}
