package fightingarts.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import fightingarts.StyleState;
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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Renders detached, animated player arms and swords in first-person view. */
public final class FirstPersonStyleRenderer {
    private static final float[] CAMERA_ORIGIN = {0, 28, 0};
    private static final float CAMERA_ARM_DEPTH = -2.0f;
    private static final float CAMERA_SIDE_INSET = 1.25f;
    private static final Map<String, Group> GROUPS = new HashMap<>();
    private static boolean loaded;
    private FirstPersonStyleRenderer() {}

    public static void render(RenderHandEvent event, CallbackInfoReturnable<Boolean> result) {
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer player = mc.player;
        if (player == null) return;
        StyleState state = AnimationRenderer.STATES.get(player.getUUID());
        if (state == null || state.swords().size() < 3 || state.phase().equals("OFF")) return;

        result.setReturnValue(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) return;

        AnimationRenderer.Pose firstPersonPose = AnimationRenderer.sampleFirstPersonPose(player, state, event.getPartialTick());
        if (firstPersonPose == null) AnimationRenderer.updateFirstPerson(player, event.getPartialTick());
        PlayerModel<?> model = renderer.getModel();
        loadRig();
        ItemStack transfer = state.phase().equals("SHEATHING") ? state.swords().get(2) : ItemStack.EMPTY;
        renderArmAndSword(player, model, HumanoidArm.LEFT, "left_item", "espada_left_item_AJUSTE",
                state.swords().get(1), transfer, event, firstPersonPose);
        renderArmAndSword(player, model, HumanoidArm.RIGHT, "right_item", "espada_right_item_AJUSTE",
                state.swords().get(0), ItemStack.EMPTY, event, firstPersonPose);
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

    private static void applyCameraBone(PoseStack pose, String boneName, float[] animation) {
        Group bone = GROUPS.get(boneName);
        if (bone == null) return;
        pose.translate((bone.origin[0] + animation[0] - CAMERA_ORIGIN[0]) / 16.0,
                (bone.origin[1] + animation[1] - CAMERA_ORIGIN[1]) / 16.0,
                (bone.origin[2] + animation[2] - CAMERA_ORIGIN[2]) / 16.0);
        float side = Math.signum(bone.origin[0] + animation[0]);
        pose.translate(-side * CAMERA_SIDE_INSET / 16.0, 0, CAMERA_ARM_DEPTH / 16.0);
        rotateCamera(pose, new float[]{bone.rotation[0] + animation[3],
                bone.rotation[1] + animation[4], bone.rotation[2] + animation[5]});
        pose.scale(animation[6], animation[7], animation[8]);
    }

    private static void renderSwordInCameraSpace(PoseStack pose, MultiBufferSource buffers, int light,
                                                AbstractClientPlayer player, ItemStack sword, String armBone,
                                                String itemBone, String adjustment, float[] animation) {
        Group arm = GROUPS.get(armBone), item = GROUPS.get(itemBone), adjust = GROUPS.get(adjustment);
        if (arm == null || item == null || adjust == null || hidden(animation)) return;
        pose.pushPose();
        translateCamera(pose, add(sub(item.origin, arm.origin), new float[]{animation[0], animation[1], animation[2]}));
        rotateCamera(pose, new float[]{item.rotation[0] + animation[3],
                item.rotation[1] + animation[4], item.rotation[2] + animation[5]});
        pose.scale(animation[6], animation[7], animation[8]);
        translateCamera(pose, sub(adjust.origin, item.origin));
        float zAlignment = armBone.equals("left_arm") ? 20.0f : -20.0f;
        rotateCamera(pose, new float[]{adjust.rotation[0], adjust.rotation[1], adjust.rotation[2] + zAlignment});
        SwordModel.render(pose, buffers, light, player, sword, true);
        pose.popPose();
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
        offset[0] += animation[0]; offset[1] += animation[1]; offset[2] += animation[2];
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
