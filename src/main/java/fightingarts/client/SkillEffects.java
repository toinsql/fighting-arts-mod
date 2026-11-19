package fightingarts.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fightingarts.SantoryuSkills;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

final class SkillEffects {
    private static final List<Timed> EFFECTS = new ArrayList<>();
    private static final Minecraft MC = Minecraft.getInstance();
    static final ResourceLocation GREEN_SPARK = resource("spark_emerald");
    static final ResourceLocation PURPLE_SPARK = resource("spark_violet");

    private SkillEffects() {}

    private static ResourceLocation resource(String name) {
        return ResourceLocation.fromNamespaceAndPath("fightingarts", "textures/effect/" + name + ".png");
    }

    static void clear() { EFFECTS.clear();MoonMarkLayer.clear();SkillImpact.clear(); }

    static void add(SantoryuSkills.Effect effect) {
        if (MC.level == null) return;
        if (effect.kind() == 2 && effect.target() >= 0
                && MC.level.getEntity(effect.target()) instanceof net.minecraft.world.entity.LivingEntity living) {
            SantoryuSkills.clientStun(living);MoonMarkLayer.add(living,effect.to());
            return;
        }
        if (effect.kind()==3 && MC.player!=null && effect.target()==MC.player.getId()) SkillImpact.trigger();
        if (EFFECTS.size() >= 256) EFFECTS.removeFirst();
        if(effect.kind()==7 && MC.level.getEntity(effect.target())==MC.player) {
            MC.player.setOnGround(false);MC.player.setDeltaMovement(effect.to());
        }
        if(effect.kind()==8 && MC.level.getEntity(effect.target()) instanceof net.minecraft.client.player.AbstractClientPlayer player)
            SkillHud.queueLanding(player.getUUID());
        long start = MC.level.getGameTime();
        long seed = 31 * start + effect.from().hashCode();
        List<Vec3> fire = effect.kind() == 3 ? firePositions(effect) : List.of();
        EFFECTS.add(new Timed(effect, start, seed, fire, effect.kind()==9 ? MoonSurface.project(effect.from(),effect.to()) : List.of()));
    }

    static void screen(GuiGraphics graphics, float partial) {
        if (MC.level == null || MC.player == null) return;
        for (Timed timed : EFFECTS) {
            double age = (MC.level.getGameTime() + partial - timed.start) / 20;
            if (timed.effect.kind() == 3 && timed.effect.target() == MC.player.getId() && age >= 0 && age < 1.20) {
                ScreenSlashes.render(graphics, age);
            }
            if (timed.effect.kind() != 6 || timed.effect.target() != MC.player.getId() || age >= .36) continue;
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(graphics.guiWidth() * .5, graphics.guiHeight() * .47, 0);
            pose.mulPose(Axis.ZP.rotationDegrees(26));
            int width = (int) (graphics.guiWidth() * (.28 + .55 * Math.min(1, age * 14)));
            int height = Math.max(14, width / 6);
            RenderSystem.enableBlend();
            graphics.setColor(1, .88f, 1, (float) Math.pow(1 - age / .36, 1.2));
            graphics.blit(SkillMesh.WIND, -width / 2, -height / 2, width, height, 0, 0, 128, 32, 128, 32);
            graphics.setColor(1, 1, 1, 1);
            pose.popPose();
        }
    }

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || MC.level == null) return;
        double now = MC.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        EFFECTS.removeIf(timed -> now - timed.start > 120);
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-eye.x, -eye.y, -eye.z);
        var buffers = MC.renderBuffers().bufferSource();
        for (Timed timed : EFFECTS) {
            double age = (now - timed.start) / 20;
            var effect = timed.effect;
            switch (effect.kind()) {
                case 1, 3 -> {
                    boolean large = effect.kind() == 3;
                    double life = large ? 1.5 : 1;
                    if (large && age < .4) pressureRing(pose,buffers,timed,age);
                    if (age < life) lightning(pose, buffers, camera, timed, age, life, large);
                    if (large && age >= .6 && age < 2.6) fire(pose, buffers, camera, timed, age - .6);
                }
                case 9 -> {
                    if(age<6)MoonSurface.render(pose,buffers,timed.patches,(float)Math.min(1,(6-age)*2),(float)Math.min(1,age*4));
                }
                case 4, 7, 8 -> {
                    if (age < 1) smoke(pose, buffers, camera, timed, age);
                }
            }
        }
        if (SkillHud.aiming() && MC.player != null) aim(pose, buffers, camera, event.getPartialTick().getGameTimeDeltaPartialTick(false));
        pose.popPose();
        for (ResourceLocation texture : new ResourceLocation[]{SkillMesh.EMERALD, SkillMesh.VIOLET, SkillMesh.FISSURE,
                SkillMesh.AIM, SkillMesh.WIND, GREEN_SPARK, PURPLE_SPARK, SkillHud.ATLAS}) {
            buffers.endBatch(SkillMesh.layer(texture));
        }
    }

    private static void pressureRing(PoseStack pose, MultiBufferSource buffers, Timed timed, double age) {
        Vec3 forward = timed.effect.to().subtract(timed.effect.from()).normalize();
        if (forward.lengthSqr() < .01) forward = new Vec3(0,0,1);
        Vec3 right = new Vec3(0,1,0).cross(forward).normalize();
        Vec3 up = forward.cross(right).normalize();
        double progress = age / .4;
        double radius = .25 + 2.6 * (1 - Math.pow(1 - progress, 2));
        Vec3 center = timed.effect.from().add(0,1.1,0).add(forward.scale(.35));
        for (int strand = 0; strand < 2; strand++) {
            SkillMesh.Knot[] ring = new SkillMesh.Knot[49];
            for (int i = 0; i < ring.length; i++) {
                double angle = i * Math.TAU / (ring.length - 1);
                Vec3 radial = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
                double wave = 1 + .025 * Math.sin(angle * 9 + strand);
                ring[i] = new SkillMesh.Knot(center.add(radial.scale(radius * wave * (1 + strand * .09))),
                        radial.scale(.13 * (1 - progress)), i / (float)(ring.length - 1), (float)(1 - progress));
            }
            SkillMesh.ribbon(pose,buffers,SkillMesh.WIND,ring,strand==0?0x80ffc3:0xd593ff);
        }
    }

    private static void lightning(PoseStack pose, MultiBufferSource buffers, Camera camera, Timed timed,
                                  double age, double life, boolean large) {
        Vec3 start = timed.effect.from().add(0, large ? 1.12 : .95, 0);
        Vec3 end = timed.effect.to().add(0, large ? 1.12 : .95, 0);
        Vec3 axis = end.subtract(start);
        if (axis.lengthSqr() < .01) return;
        Vec3 right = new Vec3(0, 1, 0).cross(axis).normalize();
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 facing = new Vec3(camera.getLookVector());
        int steps = Math.max(8, (int) Math.ceil(axis.length() * 5));
        float alpha = (float) Math.pow(Math.max(0, 1 - age / life), .65);
        double size = large ? 1 : .62;
        Random random = new Random(timed.seed);
        for (int strand = 0; strand < 2; strand++) {
            var texture = strand == 0 ? SkillMesh.EMERALD : SkillMesh.VIOLET;
            var spark = strand == 0 ? GREEN_SPARK : PURPLE_SPARK;
            Vec3[] path = new Vec3[steps + 1];
            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                double envelope = Math.pow(Math.sin(Math.PI * t), .35);
                double twist = t * (large ? 18 : 12) + strand * Math.PI + age * 2;
                double shiver = random.nextDouble(-.12, .12);
                path[i] = start.lerp(end, t).add(right.scale((Math.cos(twist) * .32 + shiver) * size * envelope))
                        .add(up.scale((Math.sin(twist) * .26 + shiver) * size * envelope));
            }
            cameraRibbon(pose, buffers, texture, path, facing, .16 * size, alpha, true);
            for (int i = 3 + strand * 2; i < steps - 2; i += 5) {
                Vec3 base = path[i];
                Vec3 fork = right.scale(random.nextDouble(-.42, .42)).add(0, random.nextDouble(.13, .46), 0)
                        .add(axis.normalize().scale(random.nextDouble(.12, .45))).scale(size);
                Vec3 tip = base.add(fork);
                cameraRibbon(pose, buffers, texture, new Vec3[]{
                        base, base.lerp(tip, .44).add(right.scale(.11 * size)), tip
                }, facing, .08 * size, alpha * .72f, true);
                Vec3 mote = tip.add(fork.scale(age * .38));
                cameraSprite(pose, buffers, camera, spark, mote, (.10 + .04 * Math.sin(age * 26 + i)) * size, alpha);
            }
            // A brief fork out to the sides keeps the discharge visible from the front.
            if (age < .36) {
                Vec3 base = path[steps - 2];
                double side = strand == 0 ? -1 : 1;
                Vec3 tip = base.add(right.scale(side * .83 * size)).add(0, .45 * size, 0);
                Vec3 bend = base.lerp(tip, .56).add(0, -.12, 0);
                float flare = (float) (1 - age / .36);
                cameraRibbon(pose, buffers, texture, new Vec3[]{base, bend, tip}, facing, .13 * size, flare, true);
                cameraSprite(pose, buffers, camera, spark, tip, .17 * size, flare);
            }
        }
    }

    private static void cameraRibbon(PoseStack pose, MultiBufferSource buffers, ResourceLocation texture,
                                     Vec3[] path, Vec3 facing, double width, float alpha, boolean taper) {
        SkillMesh.Knot[] knots = new SkillMesh.Knot[path.length];
        Vec3 previousSide = Vec3.ZERO;
        for (int i = 0; i < path.length; i++) {
            float t = i / (float) (path.length - 1);
            Vec3 tangent = path[Math.min(i + 1, path.length - 1)].subtract(path[Math.max(0, i - 1)]);
            Vec3 side = SkillMesh.facingSide(tangent, facing);
            if (side.dot(previousSide) < 0) side = side.scale(-1);
            if (side.lengthSqr() == 0) side = previousSide;
            previousSide = side;
            double profile = taper ? Math.pow(Math.sin(t * Math.PI), .34) : 1;
            knots[i] = new SkillMesh.Knot(path[i], side.scale(width * profile), t, alpha);
        }
        SkillMesh.ribbon(pose, buffers, texture, knots, 0xffffff);
    }

    private static List<Vec3> firePositions(SantoryuSkills.Effect effect) {
        List<Vec3> positions = new ArrayList<>();
        int count = Math.max(1, (int) Math.ceil(effect.from().distanceTo(effect.to()) * 2));
        for (int i = 0; i <= count; i++) {
            Vec3 point = effect.from().lerp(effect.to(), i / (double) count);
            var ground = MC.level.clip(new ClipContext(point.add(0, 1, 0), point.add(0, -3, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
            if (ground.getType() != HitResult.Type.MISS) positions.add(ground.getLocation());
        }
        return positions;
    }

    private static void fire(PoseStack pose, MultiBufferSource buffers, Camera camera, Timed timed, double age) {
        double yaw = Math.toRadians(camera.getYRot());
        Vec3 right = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        for (int i = 0; i < timed.fire.size(); i++) {
            Vec3 base = timed.fire.get(i);
            double localAge = age - .045 * (i % 4);
            if (localAge < 0) continue;
            double life = Math.min(1, localAge * 8) * Math.min(1, (2 - age) * 3);
            double height = (.75 + .35 * Math.sin(i * 2.7 + age * 8) + age * .22) * (.35 + .65 * life);
            atlasSprite(pose, buffers, 8, base.add(0, height * .5, 0), right.scale(.30),
                    new Vec3(0, height * .5, 0), (float) life);
            if (i % 3 == 0) {
                Vec3 ember = base.add(right.scale(.19 * Math.sin(i + age * 4))).add(0, height + localAge * .42, 0);
                cameraSprite(pose, buffers, camera, i % 2 == 0 ? GREEN_SPARK : PURPLE_SPARK, ember, .09, (float) life);
            }
        }
    }

    private static void smoke(PoseStack pose, MultiBufferSource buffers, Camera camera, Timed timed, double age) {
        Random random = new Random(timed.seed);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4 + random.nextDouble(-.23, .23);
            double spread = .15 + age * random.nextDouble(.6, 1.35);
            double size = .22 + age * random.nextDouble(.24, .47);
            Vec3 p = timed.effect.from().add(Math.cos(angle) * spread, size * .5 + age * .35, Math.sin(angle) * spread);
            Vec3 right = new Vec3(camera.rotation().transform(new Vector3f(1, 0, 0))).scale(size);
            Vec3 up = new Vec3(camera.rotation().transform(new Vector3f(0, 1, 0))).scale(size);
            atlasSprite(pose, buffers, 9, p, right, up, (float) (.75 * (1 - age)));
        }
    }

    private static void aim(PoseStack pose, MultiBufferSource buffers, Camera camera, float partial) {
        Vec3 start = MC.player.getPosition(partial);
        var plan = fightingarts.LeapTrajectory.aim(MC.player, start,
                start.add(0,MC.player.getEyeHeight(),0),MC.player.getViewVector(partial));
        if(plan==null)return;
        Vec3[] path=plan.path().stream().map(point->point.add(0,.12,0)).toArray(Vec3[]::new);
        SkillMesh.Knot[] beam=new SkillMesh.Knot[path.length];
        Vec3 previous=Vec3.ZERO;
        for(int i=0;i<path.length;i++) {
            Vec3 tangent=path[Math.min(i+1,path.length-1)].subtract(path[Math.max(0,i-1)]);
            Vec3 side=SkillMesh.facingSide(tangent,path[i].subtract(camera.getPosition()));
            if(side.dot(previous)<0)side=side.scale(-1);
            previous=side;
            double distance=path[i].distanceTo(camera.getPosition());
            double width=Math.min(.13,.025+distance*.004);
            float opacity=(float)(.78*Math.clamp((distance-1)/1.2,0,1));
            beam[i]=new SkillMesh.Knot(path[i],side.scale(width),0,opacity);
        }
        SkillMesh.aimRibbon(pose,buffers,beam);
        Vec3 landing=plan.landing().add(0,.045,0);
        for (int arc = 0; arc < 4; arc++) {
            SkillMesh.Knot[] ring = new SkillMesh.Knot[17];
            for (int i = 0; i < ring.length; i++) {
                float t = i / (float) (ring.length - 1);
                double angle = (arc + t) * Math.PI / 2;
                Vec3 radial = new Vec3(Math.cos(angle), 0, Math.sin(angle));
                ring[i] = new SkillMesh.Knot(landing.add(radial), radial.scale(.11), t, .9f);
            }
            SkillMesh.ribbon(pose, buffers, SkillMesh.AIM, ring, 0xffffff);
        }
    }

    private static void cameraSprite(PoseStack pose, MultiBufferSource buffers, Camera camera, ResourceLocation texture,
                                     Vec3 center, double size, float opacity) {
        Vec3 right = new Vec3(camera.rotation().transform(new Vector3f(1, 0, 0))).scale(size);
        Vec3 up = new Vec3(camera.rotation().transform(new Vector3f(0, 1, 0))).scale(size);
        SkillMesh.sprite(pose, buffers, texture, center, right, up, opacity, 0xffffff);
    }

    private static void atlasSprite(PoseStack pose, MultiBufferSource buffers, int cell, Vec3 center,
                                    Vec3 right, Vec3 up, float opacity) {
        var vertex = buffers.getBuffer(SkillMesh.layer(SkillHud.ATLAS));
        float u = cell % 4 / 4f, v = cell / 4 / 4f;
        Vec3[] points = {center.subtract(right).subtract(up), center.add(right).subtract(up),
                center.add(right).add(up), center.subtract(right).add(up)};
        for (int i = 0; i < 4; i++) {
            Vec3 p = points[i];
            vertex.addVertex(pose.last(), (float) p.x, (float) p.y, (float) p.z)
                    .setColor(255, 255, 255, Math.round(opacity * 255))
                    .setUv(u + (i == 1 || i == 2 ? .25f : 0), v + (i < 2 ? .25f : 0))
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xf000f0).setNormal(pose.last(), 0, 1, 0);
        }
    }

    private record Timed(SantoryuSkills.Effect effect, long start, long seed, List<Vec3> fire, List<MoonSurface.Patch> patches) {}
}
