package fightingarts;

import fightingarts.client.SkillHud;
import fightingarts.client.StyleClient;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

final class WaterSightCheck {
    private static final Minecraft MC = Minecraft.getInstance();
    private static LivingEntity aquatic, insideWall, outsideWall, distant;
    private static int stage, timeout;
    private static long diveTick;
    private static double surfaceHeight;
    private static double castHeight;
    private static String capture;

    static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> {
            if (capture == null) return;
            event.getGuiGraphics().flush();
            Screenshot.grab(new File("."), capture + ".png", MC.getMainRenderTarget(), message -> System.out.println(message.getString()));
            capture = null;
        });
    }

    private static void tick() {
        if (MC.player == null || MC.getSingleplayerServer() == null) return;
        MC.options.pauseOnLostFocus = false;
        if (MC.screen != null) MC.setScreen(null);
        require(++timeout < 800, "timeout at stage " + stage + ", skill status " + SkillHud.local());
        long now = MC.level.getGameTime();
        switch (stage) {
            case 0 -> { server(WaterSightCheck::prepare); stage++; }
            case 1 -> {
                if (!active(StyleMode.SIMPLE) || !MC.player.isInWater()) return;
                surfaceHeight = MC.player.getY();
                diveTick = now;
                stage = 11;
            }
            case 11 -> {
                if (now - diveTick < 12) return;
                require(surfaceHeight - MC.player.getY() > .1 && !MC.player.isSwimming(),
                        "natural water gravity works without forced upward drift");
                surfaceHeight = MC.player.getY();
                diveTick = now;
                MC.options.keyShift.setDown(true);
                stage = 2;
            }
            case 2 -> {
                if (now - diveTick < 20) return;
                require(surfaceHeight - MC.player.getY() >= .7 && MC.player.isEyeInFluid(FluidTags.WATER)
                        && !MC.player.isSwimming(), "Shift dives below the surface while the player stays upright");
                MC.options.keyShift.setDown(false);
                server(p -> {
                    require(p.isInWater() && !p.isSwimming(), "the server tracks the dive without enabling swimming");
                    p.connection.teleport(220.5, 100, -290.5, 0, 7);
                    p.setDeltaMovement(Vec3.ZERO);
                    StyleService.toggle(p, StyleMode.COMMON);
                });
                stage++;
            }
            case 3 -> {
                if (!active(StyleMode.COMMON) || !MC.player.onGround()) return;
                server(p -> {
                    p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(2), "fightingarts debug awakening");
                    StyleService.toggle(p, StyleMode.AWAKENED);
                });
                stage++;
            }
            case 4 -> {
                if (!active(StyleMode.AWAKENED) || now - StyleClient.local().phaseStart() < 8) return;
                server(p -> {
                    require(aquatic.isInWater() && p.distanceTo(aquatic) > 25 && p.hasLineOfSight(aquatic),
                            "aquatic target sits behind a water surface over 25 blocks away");
                    require(p.distanceTo(insideWall) < 25 && p.distanceTo(outsideWall) > 25
                            && !p.hasLineOfSight(insideWall) && !p.hasLineOfSight(outsideWall), "stone walls hide both boundary targets");
                    require(SantoryuSkills.sightReveals(p, aquatic) && SantoryuSkills.sightReveals(p, insideWall)
                            && !SantoryuSkills.sightReveals(p, outsideWall) && !SantoryuSkills.sightReveals(p, distant),
                            "server uses the 25-block wall limit and 30-block total range");
                    p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(2), "fightingarts debug reset");
                    p.connection.teleport(220.5, 105, -290.5, 0, 7);
                    p.setDeltaMovement(Vec3.ZERO);
                });
                stage = 12;
            }
            case 12 -> {
                if (MC.player.onGround() || MC.player.getY() < 102.5) return;
                castHeight = MC.player.getY();
                SantoryuSkills.request(6, 0);
                stage = 13;
            }
            case 13 -> {
                var status = SkillHud.local();
                if (status == null || status.visionStart() < 0 || now - status.visionStart() < 6) return;
                require(status.skill() == 6 && MC.player.getY() < castHeight - .5,
                        "gravity continues during the vision entrance animation");
                stage = 5;
            }
            case 5 -> {
                var status = SkillHud.local();
                if (status == null || status.visionStart() < 0 || now - status.visionStart() < 32) return;
                require(rendered(aquatic) && reported(aquatic), "the submerged mob is sent and rendered from the bank");
                require(rendered(insideWall) && reported(insideWall) && !rendered(outsideWall) && !reported(outsideWall),
                        "the client sees the wall target within 25 blocks");
                capture = "sight-over-water";
                stage++;
            }
            case 6 -> {
                if (capture != null) return;
                server(p -> {
                    p.connection.teleport(220.5, 97.3, -278.5, 0, 3);
                    p.setDeltaMovement(Vec3.ZERO);
                });
                stage++;
            }
            case 7 -> {
                if (!MC.player.isEyeInFluid(FluidTags.WATER) || !rendered(aquatic)) return;
                capture = "sight-underwater";
                stage++;
            }
            case 8 -> {
                if (capture != null) return;
                System.out.println("FA-REVIEW PASS upright dive and submerged silhouettes with a 25-block wall radius");
                stage++;
                MC.stop();
            }
        }
    }

    private static void prepare(ServerPlayer p) {
        p.setGameMode(GameType.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.serverLevel().setDayTime(6000);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        for (int i = 0; i < 3; i++) StyleWeapons.of(p).setItem(i, new ItemStack(Items.IRON_SWORD));
        for (int x = 210; x <= 231; x++) for (int z = -294; z <= -256; z++) {
            boolean pool = x >= 218 && x <= 222 && z >= -286 && z <= -260;
            for (int y = 95; y <= 105; y++) {
                var block = y > 99 ? Blocks.AIR : pool && y > 95 ? Blocks.WATER : Blocks.STONE;
                p.level().setBlock(new BlockPos(x,y,z), block.defaultBlockState(), 3);
            }
        }
        for (int x : new int[]{214, 215, 216, 217, 218, 222, 223, 224, 225, 226, 227}) {
            for (int y = 100; y < 105; y++) p.level().setBlock(new BlockPos(x,y,-276), Blocks.STONE.defaultBlockState(), 3);
        }
        p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new AABB(210, 95, -294, 232, 107, -255))
                .forEach(net.minecraft.world.entity.Entity::discard);
        p.connection.teleport(220.5, 98.5, -283.5, 0, 0);
        aquatic = spawn(p, EntityType.SQUID, new Vec3(220.5, 97.5, -264.0));
        insideWall = spawn(p, EntityType.COW, new Vec3(215.5, 100, -266.5));
        outsideWall = spawn(p, EntityType.COW, new Vec3(225.5, 100, -265.5));
        distant = spawn(p, EntityType.COW, new Vec3(220.5, 100, -258.5));
        StyleService.toggle(p, StyleMode.SIMPLE);
        MC.execute(() -> {
            MC.options.setCameraType(CameraType.FIRST_PERSON);
            MC.options.renderDistance().set(6);
            MC.options.simulationDistance().set(5);
        });
    }

    private static LivingEntity spawn(ServerPlayer player, EntityType<? extends net.minecraft.world.entity.Mob> type, Vec3 position) {
        var mob = type.create(player.level());
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setPos(position);
        player.serverLevel().addFreshEntity(mob);
        return mob;
    }

    private static boolean active(StyleMode mode) {
        var style = StyleClient.local();
        return style != null && style.mode() == mode && style.phase().equals("ACTIVE");
    }

    private static boolean rendered(LivingEntity mob) {
        return ((List<?>)sightField("visible")).stream().anyMatch(entity -> ((LivingEntity)entity).getId() == mob.getId());
    }

    private static boolean reported(LivingEntity mob) {
        return ((Map<?,?>)sightField("targets")).containsKey(mob.getId());
    }

    private static Object sightField(String name) {
        try {
            var field = Class.forName("fightingarts.client.BlindSight").getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void server(Consumer<ServerPlayer> action) {
        var server = MC.getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayers().getFirst()));
    }

    private static void require(boolean check, String description) {
        if (!check) throw new AssertionError("FA-WATER-SIGHT FAIL " + description);
    }
}
