package fightingarts.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fightingarts.StyleState;
import fightingarts.StyleService;
import fightingarts.client.mixin.CombatEatRendererInvoker;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Renders detached, animated player arms and swords in first-person view. */
public final class FirstPersonStyleRenderer {
    private static final float[] CAMERA_ORIGIN = {0, 28, 0};
    private static final double SIGHT_HAND_TRAVEL = 1.65;
    private static final float CAMERA_ARM_DEPTH = -2.0f;
    private static final float CAMERA_SIDE_INSET = 1.25f;
    private static final Map<String, Group> GROUPS = new HashMap<>();
    private static boolean loaded;
    private static final double[] idleBladeLength = new double[2];
    private FirstPersonStyleRenderer() {}

    public static boolean ownsHands() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        StyleState state = AnimationRenderer.STATES.get(mc.player.getUUID());
        return state != null && !state.phase().equals("OFF") && state.swords().size() >= 3;
    }

    public static boolean usesStyleHands(float partialTick) {
        return ownsHands() && !loweringVanillaHand(partialTick) && vanillaHandOffset(partialTick) <= 0;
    }

    public static void render(RenderHandEvent event, CallbackInfoReturnable<Boolean> result) {
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer player = mc.player;
        double sightHands=mc.level==null?0:BlindSight.handProgress(mc.level.getGameTime()+event.getPartialTick());
        if (player != null && !BlindSight.active() && AnimationRenderer.firstPersonHasStrike(player)) sightHands = 0;
        if(sightHands>=1){result.setReturnValue(true);return;}
        if (mc.level != null) {
            StyleState style = StyleClient.local();
            double now = mc.level.getGameTime() + (double) event.getPartialTick();
            if (AttackHotbar.loweringVanillaHand(style, now) || AttackHotbar.vanillaHandOffset(style, now) > 0) {
                result.setReturnValue(false);
                return;
            }
        }
        if (!ownsHands()) return;
        StyleState state = AnimationRenderer.STATES.get(player.getUUID());

        result.setReturnValue(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) return;

        loadRig();
        AnimationRenderer.Pose firstPersonPose = AnimationRenderer.sampleFirstPersonPose(player, state, event.getPartialTick());
        if (firstPersonPose == null) AnimationRenderer.updateFirstPerson(player, event.getPartialTick());
        PlayerModel<?> model = renderer.getModel();
        ItemStack transfer = state.phase().equals("SHEATHING") && state.mode().hasSkills()
                ? state.swords().get(2) : state.phase().equals("UPGRADING") ? state.swords().get(0) : ItemStack.EMPTY;
        double spin = SwordModel.spinAge(player, event.getPartialTick());
        double upgradeTime = StyleService.upgradeAnimationSeconds(player.level().getGameTime() - state.phaseStart()
                + (double) event.getPartialTick());
        ItemStack rightSword = state.swords().get(state.phase().equals("UPGRADING") && upgradeTime >= 1.29167
                && upgradeTime < 1.66667 ? 2 : 0);
        var hands=event.getPoseStack();
        hands.pushPose();
        if(sightHands>0){
            // Lower both arms and swords as a group in camera space, regardless of the casting pose.
            double progress=AttackHotbar.ease(sightHands);
            hands.translate(0,-SIGHT_HAND_TRAVEL*progress*progress,0);
        }
        var consumption = state.phase().equals("ACTIVE") && !AnimationRenderer.firstPersonStriking(player, true)
                ? ConsumableClient.frame(player, event.getPartialTick()) : null;
        float switching = consumption == null ? 0 : consumption.draw();
        if (switching < .75f) {
            hands.pushPose();
            hands.translate(0, -2 * switching, 0);
            renderArmAndSword(player, model, HumanoidArm.LEFT, "left_item", "espada_left_item_AJUSTE",
                    spin >= 0 ? ItemStack.EMPTY : state.swords().get(1), transfer, event, firstPersonPose);
            hands.popPose();
        }
        if (switching > .4f) renderConsumable(mc, player, model, event, consumption);
        renderArmAndSword(player, model, HumanoidArm.RIGHT, "right_item", "espada_right_item_AJUSTE",
                spin >= 0 ? ItemStack.EMPTY : rightSword, ItemStack.EMPTY, event, firstPersonPose);
        if (spin >= 0 && firstPersonPose != null) {
            var pose = event.getPoseStack();
            var right = gripCenter(player, firstPersonPose, false);
            var left = gripCenter(player, firstPersonPose, true);
            // Center between the visible grips even if one hand is closer to the camera.
            float depth = right.z + left.z;
            float midpoint = depth < -.01f ? Math.clamp(right.z / depth, 0f, 1f) : .5f;
            var center = right.lerp(left, midpoint);
            float scale = (float) (projectedIdleBladeLength(player) * Math.max(.08, -center.z) / SwordModel.GUARD_TO_TIP);
            pose.pushPose();
            pose.translate(center.x, center.y, center.z);
            pose.scale(scale, scale, scale);
            SwordModel.renderSpin(pose, event.getMultiBufferSource(), event.getPackedLight(),
                    player, state.swords(), true, spin);
            pose.popPose();
        }
        hands.popPose();
    }

    private static void renderConsumable(Minecraft mc, AbstractClientPlayer player, PlayerModel<?> model,
                                         RenderHandEvent event, ConsumableClient.Frame use) {
        PoseStack pose = event.getPoseStack();
        var items = mc.gameRenderer.itemInHandRenderer;
        var vanilla = (CombatEatRendererInvoker) items;
        var buffers = event.getMultiBufferSource();
        int light = event.getPackedLight();
        var itemPose = new PoseStack();
        itemPose.translate(0, -1.6 * (1 - use.draw()), 0);
        vanilla.fightingarts$eatTransform(itemPose, event.getPartialTick(), HumanoidArm.LEFT, use.stack(), player);
        vanilla.fightingarts$itemArmTransform(itemPose, HumanoidArm.LEFT, 0);
        var itemTransform = itemPose.last().pose();
        pose.pushPose();
        pose.mulPose(itemTransform);
        items.renderItem(player, use.stack(), ItemDisplayContext.FIRST_PERSON_LEFT_HAND, true, pose, buffers, light);
        pose.popPose();

        // The detached arm pivots at the shoulder. Put its wrist at the animated item grip.
        var grip = itemTransform.transformPosition(new Vector3f(-.18f, -.06f, .18f));
        float wristX = (1 - SwordModel.slimHandOffset(player, true)) / 16f;
        pose.pushPose();
        pose.translate(grip.x - .14f, grip.y + .12f, grip.z + .08f);
        pose.mulPose(Axis.XP.rotationDegrees(-20));
        pose.mulPose(Axis.ZP.rotationDegrees(-32));
        pose.translate(-wristX, -8.5f / 16, 0);
        var skin = buffers.getBuffer(RenderType.entityCutoutNoCull(player.getSkin().texture()));
        renderDetached(model.leftArm, pose, skin, light);
        renderDetached(model.leftSleeve, pose, skin, light);
        pose.popPose();
    }

    private static double projectedIdleBladeLength(AbstractClientPlayer player) {
        int model = SwordModel.slimHandOffset(player, true) == 0 ? 0 : 1;
        if (idleBladeLength[model] > 0) return idleBladeLength[model];
        var idle = AnimationRenderer.firstPersonIdlePose();
        double fallback = SwordModel.GUARD_TO_TIP * 2 / 1.45;
        if (idle == null) return fallback;
        double total = 0;
        for (String side : new String[]{"right", "left"}) {
            String arm = side + "_arm", item = side + "_item";
            String adjustment = "espada_" + item + "_AJUSTE";
            if (!GROUPS.containsKey(arm) || !GROUPS.containsKey(item) || !GROUPS.containsKey(adjustment)) return fallback;
            var pose = new PoseStack();
            applyCameraBone(pose, arm, idle.transform(arm));
            placeCameraSword(pose, player, arm, item, adjustment, idle.transform(item));
            float guard = SwordModel.GUARD_COORDINATE * SwordModel.ITEM_SCALE;
            float tip = SwordModel.TIP_COORDINATE * SwordModel.ITEM_SCALE;
            var matrix = pose.last().pose();
            var a = matrix.transformPosition(new org.joml.Vector3f(guard, guard, 0));
            var b = matrix.transformPosition(new org.joml.Vector3f(tip, tip, 0));
            if (a.z >= -.05 || b.z >= -.05) return fallback;
            total += Math.hypot(a.x / -a.z - b.x / -b.z, a.y / -a.z - b.y / -b.z);
        }
        return idleBladeLength[model] = total / 2;
    }

    private static org.joml.Vector3f gripCenter(AbstractClientPlayer player, AnimationRenderer.Pose animation, boolean left) {
        String arm = left ? "left_arm" : "right_arm";
        PoseStack local = new PoseStack();
        applyCameraBone(local, arm, animation.transform(arm));
        // Hand center in the detached arm mesh, measured from the shoulder pivot.
        float x = ((left ? 1 : -1) - SwordModel.slimHandOffset(player, left)) / 16f;
        return local.last().pose().transformPosition(new org.joml.Vector3f(x, -8f / 16, 0));
    }

    public static double vanillaHandOffset(float partialTick) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        return AttackHotbar.vanillaHandOffset(StyleClient.local(), mc.level.getGameTime() + (double) partialTick);
    }

    public static boolean loweringVanillaHand(float partialTick) {
        var mc = Minecraft.getInstance();
        return mc.level != null
                && AttackHotbar.loweringVanillaHand(StyleClient.local(), mc.level.getGameTime() + (double) partialTick);
    }

    private static void renderArmAndSword(AbstractClientPlayer player, PlayerModel<?> model,
                                          HumanoidArm side, String itemBone, String adjustment,
                                          ItemStack sword, ItemStack transfer, RenderHandEvent event,
                                          AnimationRenderer.Pose firstPersonPose) {
        PoseStack pose = event.getPoseStack();
        boolean right = side == HumanoidArm.RIGHT;
        String armBone = right ? "right_arm" : "left_arm";
        ModelPart arm = right ? model.rightArm : model.leftArm;
        ModelPart sleeve = right ? model.rightSleeve : model.leftSleeve;
        ResourceLocation skin = player.getSkin().texture();
        var vertex = event.getMultiBufferSource().getBuffer(RenderType.entityCutoutNoCull(skin));
        int light = event.getPackedLight();

        pose.pushPose();
        float[] armAnimation = firstPersonPose != null
                ? firstPersonPose.transform(armBone)
                : AnimationRenderer.transform(player.getUUID(), armBone);
        float[] itemAnimation = firstPersonPose != null
                ? firstPersonPose.transform(itemBone)
                : AnimationRenderer.transform(player.getUUID(), itemBone);
        if (firstPersonPose != null) {
            applyCameraBone(pose, armBone, armAnimation);
            pose.pushPose();
            // Minecraft's arm mesh uses positive local Y down; the authored Blockbench
            // rig uses positive Y up. Reflect the local axis while keeping its dimensions.
            pose.scale(1, -1, 1);
            renderDetached(arm, pose, vertex, light);
            renderDetached(sleeve, pose, vertex, light);
            pose.popPose();
        } else {
            pose.translate(right ? 0.35 : -0.35, -0.75 + event.getEquipProgress() * 0.04, -0.25);
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-60));
            applyBone(pose, armAnimation);
            renderDetached(arm, pose, vertex, light);
            renderDetached(sleeve, pose, vertex, light);
        }

        AwakeningEffects.hand(pose, event.getMultiBufferSource(), player, event.getPartialTick(), firstPersonPose != null, !right);
        if (firstPersonPose != null) {
            renderSwordInCameraSpace(pose, event.getMultiBufferSource(), light, player, sword,
                    armBone, itemBone, adjustment, itemAnimation);
            if (!transfer.isEmpty()) {
                renderSwordInCameraSpace(pose, event.getMultiBufferSource(), light, player, transfer,
                        armBone, "left_item2", "espada_left_item2_AJUSTE", firstPersonPose.transform("left_item2"));
            }
        } else {
            renderSword(pose, event.getMultiBufferSource(), light, player, sword, armBone, itemBone,
                    adjustment, itemAnimation);
        }
        pose.popPose();
    }

    static void positionFallingArms(AnimationRenderer.Pose pose) {
        // Apply camera clearance before blending so it moves with the animated hands.
        for (String arm : new String[]{"left_arm", "right_arm"}) {
            Group bone = GROUPS.get(arm);
            if (bone == null) continue;
            float[] animation = pose.transform(arm);
            float side = Math.signum(bone.origin[0]);
            float baseX = bone.origin[0] - CAMERA_ORIGIN[0] - side * CAMERA_SIDE_INSET;
            float x = side * Math.max(3.5f, (baseX + animation[0]) * side) - baseX;
            pose.position().put(arm, new float[]{x, animation[1], animation[2] - 4});
        }
    }

    private static void applyCameraBone(PoseStack pose, String boneName, float[] animation) {
        Group bone = GROUPS.get(boneName);
        if (bone == null) return;
        float side = Math.signum(bone.origin[0]);
        float x = bone.origin[0] + animation[0] - CAMERA_ORIGIN[0] - side * CAMERA_SIDE_INSET;
        pose.translate(x / 16.0,
                (bone.origin[1] + animation[1] - CAMERA_ORIGIN[1]) / 16.0,
                (bone.origin[2] + animation[2] - CAMERA_ORIGIN[2] + CAMERA_ARM_DEPTH) / 16.0);
        rotateCamera(pose, new float[]{bone.rotation[0] + animation[3],
                bone.rotation[1] + animation[4], bone.rotation[2] + animation[5]});
        pose.scale(animation[6], animation[7], animation[8]);
    }

    private static void renderSwordInCameraSpace(PoseStack pose, MultiBufferSource buffers, int light,
                                                AbstractClientPlayer player, ItemStack sword, String armBone,
                                                String itemBone, String adjustment, float[] animation) {
        Group arm = GROUPS.get(armBone), item = GROUPS.get(itemBone), adjust = GROUPS.get(adjustment);
        if (sword.isEmpty() || arm == null || item == null || adjust == null || hidden(animation)) return;
        pose.pushPose();
        placeCameraSword(pose, player, armBone, itemBone, adjustment, animation);
        SwordModel.render(pose, buffers, light, player, sword, true);
        pose.popPose();
    }

    private static void placeCameraSword(PoseStack pose, AbstractClientPlayer player, String armBone,
                                         String itemBone, String adjustment, float[] animation) {
        Group arm = GROUPS.get(armBone), item = GROUPS.get(itemBone), adjust = GROUPS.get(adjustment);
        translateCamera(pose, add(sub(item.origin, arm.origin), new float[]{animation[0] + SwordModel.slimHandOffset(player, armBone.equals("left_arm")), animation[1], animation[2]}));
        rotateCamera(pose, new float[]{item.rotation[0] + animation[3],
                item.rotation[1] + animation[4], item.rotation[2] + animation[5]});
        pose.scale(animation[6], animation[7], animation[8]);
        translateCamera(pose, sub(adjust.origin, item.origin));
        float zAlignment = armBone.equals("left_arm") ? 20.0f : -20.0f;
        rotateCamera(pose, new float[]{adjust.rotation[0], adjust.rotation[1], adjust.rotation[2] + zAlignment});
    }

    private static void loadRig() {
        if (loaded) return;
        loaded = true;
        try (var stream = Minecraft.getInstance().getResourceManager()
                .getResourceOrThrow(ResourceLocation.fromNamespaceAndPath("fightingarts", "styles/santoryu_rig.json")).open();
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (JsonElement entry : root.getAsJsonArray("groups")) {
                JsonObject group = entry.getAsJsonObject();
                String name = group.get("name").getAsString();
                GROUPS.put(name, new Group(vec(group.getAsJsonArray("origin")), vec(group.getAsJsonArray("rotation"))));
            }
        } catch (Exception ignored) {
            GROUPS.clear();
        }
    }

    private static void renderSword(PoseStack pose, MultiBufferSource buffers, int light,
                                    AbstractClientPlayer player, ItemStack sword, String armBone,
                                    String itemBone, String adjustment, float[] animation) {
        Group arm = GROUPS.get(armBone), item = GROUPS.get(itemBone), adjust = GROUPS.get(adjustment);
        if (arm == null || item == null || adjust == null || hidden(animation)) return;
        pose.pushPose();
        float[] offset = sub(item.origin, arm.origin);
        offset[0] += animation[0] + SwordModel.slimHandOffset(player, armBone.equals("left_arm")); offset[1] += animation[1]; offset[2] += animation[2];
        translate(pose, offset);
        rotate(pose, new float[]{item.rotation[0] + animation[3], item.rotation[1] + animation[4], item.rotation[2] + animation[5]});
        pose.scale(animation[6], animation[7], animation[8]);
        translate(pose, sub(adjust.origin, item.origin));
        rotate(pose, adjust.rotation);
        SwordModel.render(pose, buffers, light, player, sword, false);
        pose.popPose();
    }

    private static boolean hidden(float[] animation) {
        return animation[6] == 0 && animation[7] == 0 && animation[8] == 0;
    }

    private static void renderDetached(ModelPart part, PoseStack pose,
                                       com.mojang.blaze3d.vertex.VertexConsumer vertex, int light) {
        PartPose savedPose = part.storePose();
        float savedXScale = part.xScale, savedYScale = part.yScale, savedZScale = part.zScale;
        boolean savedVisible = part.visible;
        boolean savedSkipDraw = part.skipDraw;
        try {
            part.setPos(0, 0, 0);
            part.setRotation(0, 0, 0);
            part.xScale = part.yScale = part.zScale = 1;
            part.visible = true;
            part.skipDraw = false;
            part.render(pose, vertex, light, OverlayTexture.NO_OVERLAY);
        } finally {
            part.loadPose(savedPose);
            part.xScale = savedXScale;
            part.yScale = savedYScale;
            part.zScale = savedZScale;
            part.visible = savedVisible;
            part.skipDraw = savedSkipDraw;
        }
    }

    private static void applyBone(PoseStack pose, float[] transform) {
        pose.translate(-transform[0] / 16.0, -transform[1] / 16.0, transform[2] / 16.0);
        pose.mulPose(new org.joml.Quaternionf().rotationZYX(
                (float)Math.toRadians(transform[5]),
                (float)Math.toRadians(-transform[4]),
                (float)Math.toRadians(-transform[3])));
        pose.scale(transform[6], transform[7], transform[8]);
    }

    private static void translate(PoseStack pose, float[] delta) {
        pose.translate(-delta[0] / 16.0, -delta[1] / 16.0, delta[2] / 16.0);
    }

    private static void translateCamera(PoseStack pose, float[] delta) {
        pose.translate(delta[0] / 16.0, delta[1] / 16.0, delta[2] / 16.0);
    }

    private static void rotate(PoseStack pose, float[] rotation) {
        pose.mulPose(new org.joml.Quaternionf().rotationZYX(
                (float)Math.toRadians(rotation[2]), (float)Math.toRadians(-rotation[1]),
                (float)Math.toRadians(-rotation[0])));
    }

    private static void rotateCamera(PoseStack pose, float[] rotation) {
        pose.mulPose(new org.joml.Quaternionf().rotationZYX(
                (float)Math.toRadians(rotation[2]), (float)Math.toRadians(rotation[1]),
                (float)Math.toRadians(rotation[0])));
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] add(float[] a, float[] b) {
        return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static float[] vec(JsonArray array) {
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsFloat();
        return values;
    }

    private record Group(float[] origin, float[] rotation) {}
}
