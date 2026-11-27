package fightingarts;

import com.mojang.blaze3d.platform.InputConstants;
import fightingarts.client.SkillHud;
import fightingarts.client.StyleClient;
import java.io.File;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

final class ConsumablesCheck {
    private static final Minecraft MC = Minecraft.getInstance();
    private static CombatConsumables.Snapshot start, stop;
    private static int stage, timeout;
    private static String capture;
    private static long aimTick;
    private static Vec3 launchPosition;
    private static double upgradeHeight;
    private static int launchEnergy;

    static void install() {
        var receiver = StyleNetwork.clientConsumableReceiver;
        StyleNetwork.clientConsumableReceiver = snapshot -> {
            receiver.accept(snapshot);
            if (MC.player != null && MC.player.getUUID().equals(snapshot.player())) {
                if (snapshot.duration() > 0) start = snapshot;
                else stop = snapshot;
            }
        };
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> {
            if (capture == null) return;
            event.getGuiGraphics().flush();
            Screenshot.grab(new File("."), capture + ".png", MC.getMainRenderTarget(), text -> System.out.println(text.getString()));
            capture = null;
        });
    }

    private static void tick() {
        if (MC.player == null || MC.getSingleplayerServer() == null) return;
        MC.options.pauseOnLostFocus = false;
        if (MC.screen != null) MC.setScreen(null);
        require(++timeout < 700, "fixture timeout at stage " + stage);
        var inventory = MC.player.getInventory();
        switch (stage) {
            case 0 -> {
                server(ConsumablesCheck::prepare);
                stage++;
            }
            case 1 -> {
                if (!active(StyleMode.SIMPLE, 12) || CombatConsumables.firstSlot(inventory) != 2) return;
                capture = "consumable-simple-slot";
                press();
                release();
                stage++;
            }
            case 2 -> {
                if (start == null) return;
                require(start.stack().is(Items.APPLE), "leftmost slot must be used before the potion");
                require(start.duration() == 22, "vanilla 32-tick food becomes 22 ticks");
                stage++;
            }
            case 3 -> {
                if (stop == null || MC.level.getGameTime() - stop.tick() < 6) return;
                require(stop.tick() - start.tick() < start.duration(), "early Q release cancels the use");
                require(inventory.getItem(2).getCount() == 1 && MC.player.getFoodData().getFoodLevel() == 8,
                        "cancelled eating preserves the stack and hunger");
                press();
                stage++;
            }
            case 4 -> {
                if (start == null || MC.level.getGameTime() - start.tick() < 12) return;
                capture = "consumable-first-person-eat";
                stage++;
            }
            case 5 -> {
                if (stop == null) return;
                release();
                require(stop.tick() - start.tick() == 22, "food duration was " + (stop.tick() - start.tick()) + " ticks");
                if (MC.level.getGameTime() - stop.tick() < 2) return;
                require(inventory.getItem(2).isEmpty() && MC.player.getFoodData().getFoodLevel() == 12,
                        "eating removes one apple and fills vanilla hunger");
                require(CombatConsumables.firstSlot(inventory) == 5, "display advances to the potion after eating");
                require(inventory.selected == 8 && MC.player.getOffhandItem().isEmpty(), "hotbar selection and offhand stay unchanged");
                stage = 20;
            }
            case 6 -> {
                if (!active(StyleMode.COMMON, 10) || MC.player.getFoodData().getFoodLevel() != 20) return;
                require(inventory.getItem(2).is(Items.APPLE) && CombatConsumables.firstSlot(inventory) == 5,
                        "a full hunger bar cannot block the drinkable potion farther right");
                bindDrop(GLFW.GLFW_KEY_G);
                MC.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                press();
                stage++;
            }
            case 7 -> {
                if (start == null || MC.level.getGameTime() - start.tick() < 11) return;
                require(start.stack().is(Items.POTION) && start.duration() == 22, "potion uses the same speed multiplier");
                capture = "consumable-third-person-drink";
                stage++;
            }
            case 8 -> {
                if (stop == null) return;
                release();
                require(stop.tick() - start.tick() == 22, "potion duration was " + (stop.tick() - start.tick()) + " ticks");
                require(inventory.getItem(5).is(Items.GLASS_BOTTLE), "the potion leaves its bottle in its original slot");
                require(inventory.getItem(2).is(Items.APPLE), "the full-hunger apple was not consumed");
                require(MC.player.hasEffect(MobEffects.MOVEMENT_SPEED), "the potion grants its vanilla effect");
                server(p -> {
                    p.getInventory().setItem(2, ItemStack.EMPTY);
                    p.getFoodData().setFoodLevel(12);
                    p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(2),
                            "fightingarts debug awakening");
                    StyleService.toggle(p, StyleMode.AWAKENED);
                });
                stage++;
            }
            case 9 -> {
                if (!active(StyleMode.AWAKENED, 10)) return;
                MC.options.setCameraType(CameraType.FIRST_PERSON);
                press();
                stage++;
            }
            case 10 -> {
                if (start == null || MC.level.getGameTime() - start.tick() < 10) return;
                require(start.stack().is(Items.COOKED_BEEF), "remaining food can be used while awakened");
                capture = "consumable-awakened-slot";
                stage++;
            }
            case 11 -> {
                if (stop == null) return;
                release();
                require(inventory.getItem(7).isEmpty() && MC.player.getFoodData().getFoodLevel() == 20,
                        "awakened eating preserves survival behavior");
                stage++;
            }
            case 12 -> {
                if (MC.level.getGameTime() - stop.tick() < 6) return;
                capture = "consumable-awakened-empty";
                stage++;
            }
            case 13 -> {
                if (capture != null) return;
                System.out.println("FA-REVIEW PASS keyboard consumables in all attack modes and a charged simple leap");
                stage++;
                MC.stop();
            }
            case 20 -> {
                if (MC.level.getGameTime() - stop.tick() < 6 || !MC.player.onGround() || SkillHud.local() == null) return;
                var status = SkillHud.local();
                for (int i = 0; i < SantoryuSkills.SIMPLE_CHARGES; i++) {
                    require(status.guardReady(i) <= MC.level.getGameTime(), "five simple charges are initially available");
                }
                MC.player.setXRot(-25);
                aimTick = MC.level.getGameTime();
                launchPosition = MC.player.position();
                require(Math.abs(leapRange(StyleMode.SIMPLE) - 15) < .02, "simple leap preview lands at 15 blocks");
                require(Math.abs(leapRange(StyleMode.COMMON) - 20) < .02, "common leap preview lands at 20 blocks");
                require(Math.abs(leapRange(StyleMode.AWAKENED) - 25) < .02, "awakened leap preview lands at 25 blocks");
                launchEnergy = status.energy();
                keyboard(GLFW.GLFW_KEY_4, GLFW.GLFW_PRESS);
                stage++;
            }
            case 21 -> {
                if (MC.level.getGameTime() - aimTick < 8) return;
                require(!SkillHud.local().leapFlight(), "holding 4 only aims");
                capture = "simple-leap-aim";
                keyboard(GLFW.GLFW_KEY_4, GLFW.GLFW_RELEASE);
                stage++;
            }
            case 22 -> {
                var status = SkillHud.local();
                if (!status.leapFlight() || MC.player.getY() < launchPosition.y + .6) return;
                require(status.skill() == 4 && status.energy() >= launchEnergy, "simple leap is real and spends no energy");
                require(status.guardReady(0) == status.start() + StyleDefense.CHARGE_COOLDOWN_TICKS,
                        "simple leap spends a defense charge with its original 15-second recharge");
                for (int i = 1; i < SantoryuSkills.SIMPLE_CHARGES; i++) {
                    require(status.guardReady(i) <= MC.level.getGameTime(), "leap spends exactly one charge");
                }
                stage++;
            }
            case 23 -> {
                if (SkillHud.local().leapFlight() || !MC.player.onGround()) return;
                double traveled = MC.player.position().subtract(launchPosition).horizontalDistance();
                require(Math.abs(traveled - 15) < .75, "simple leap travels about 15 blocks on flat ground, traveled " + traveled);
                MC.player.setXRot(0);
                server(p -> {
                    p.getInventory().setItem(2, new ItemStack(Items.APPLE));
                    p.getFoodData().setFoodLevel(20);
                    p.setHealth(p.getMaxHealth());
                    p.connection.teleport(p.getX(), p.getY() + 7, p.getZ(), 0, 0);
                    p.setDeltaMovement(Vec3.ZERO);
                });
                stage = 24;
            }
            case 24 -> {
                if (MC.player.onGround() || MC.player.getY() <= launchPosition.y + 4) return;
                upgradeHeight = MC.player.getY();
                keyboard(GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS);
                keyboard(GLFW.GLFW_KEY_X, GLFW.GLFW_RELEASE);
                stage++;
            }
            case 25 -> {
                var style = StyleClient.local();
                if (style == null || !style.phase().equals("UPGRADING") || !MC.player.onGround()) return;
                require(MC.player.getY() < upgradeHeight - 4, "the player falls and lands while the upgrade cinematic is playing");
                System.out.println("FA-UPGRADE-GRAVITY PASS midair X allows a normal fall during the cinematic");
                stage = 6;
            }
        }
    }

    private static void prepare(ServerPlayer player) {
        player.setGameMode(GameType.SURVIVAL);
        player.serverLevel().setDayTime(6000);
        for (int x = 214; x <= 227; x++) for (int z = -297; z <= -263; z++) {
            player.level().setBlock(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 100; y <= 121; y++) player.level().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        player.connection.teleport(220.5, 100, -290.5, 0, 0);
        player.getInventory().selected = 8;
        for (int i = 0; i < 9; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        player.getInventory().setItem(2, new ItemStack(Items.APPLE));
        player.getInventory().setItem(5, PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
        player.getInventory().setItem(7, new ItemStack(Items.COOKED_BEEF));
        player.getFoodData().setFoodLevel(8);
        for (int i = 0; i < 3; i++) StyleWeapons.of(player).setItem(i, new ItemStack(Items.IRON_SWORD));
        StyleService.toggle(player, StyleMode.SIMPLE);
        MC.execute(() -> {
            MC.options.setCameraType(CameraType.FIRST_PERSON);
            bindDrop(GLFW.GLFW_KEY_Q);
            MC.options.renderDistance().set(6);
            MC.options.simulationDistance().set(5);
        });
    }

    private static double leapRange(StyleMode mode) {
        return LeapTrajectory.aim(MC.player, launchPosition, MC.player.getEyePosition(), MC.player.getLookAngle(), mode)
                .landing().subtract(launchPosition).horizontalDistance();
    }

    private static boolean active(StyleMode mode, int age) {
        var state = StyleClient.local();
        return state != null && state.mode() == mode && state.phase().equals("ACTIVE")
                && MC.level.getGameTime() - state.phaseStart() >= age;
    }

    private static void press() {
        start = stop = null;
        keyboard(GLFW.GLFW_KEY_Q, GLFW.GLFW_PRESS);
    }

    private static void release() {
        keyboard(GLFW.GLFW_KEY_Q, GLFW.GLFW_RELEASE);
    }

    private static void keyboard(int key, int action) {
        MC.keyboardHandler.keyPress(MC.getWindow().getWindow(), key, GLFW.glfwGetKeyScancode(key), action, 0);
    }

    private static void bindDrop(int key) {
        MC.options.keyDrop.setKey(InputConstants.Type.KEYSYM.getOrCreate(key));
        KeyMapping.resetMapping();
    }

    private static void server(Consumer<ServerPlayer> action) {
        var server = MC.getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayers().getFirst()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("FA-CONSUMABLE FAIL " + message);
    }
}
