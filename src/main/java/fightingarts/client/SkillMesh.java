package fightingarts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Textured strips with shared edges, smooth bends and per-point width and opacity. */
final class SkillMesh {
    static final ResourceLocation EMERALD = texture("lightning_emerald");
    static final ResourceLocation VIOLET = texture("lightning_violet");
    static final ResourceLocation FISSURE = texture("moon_fissure");
    static final ResourceLocation WIND = texture("sword_wind");
    static final ResourceLocation AIM = texture("aim_ribbon");

    private SkillMesh() {}

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath("fightingarts", "textures/effect/" + name + ".png");
    }

    static RenderType layer(ResourceLocation texture) {
        return RenderType.entityTranslucentEmissive(texture);
    }

    record Knot(Vec3 center, Vec3 side, float u, float alpha) {}

    static void ribbon(PoseStack pose, MultiBufferSource buffers, ResourceLocation texture, Knot[] knots, int tint) {
        var out = buffers.getBuffer(layer(texture));
        for (int i = 1; i < knots.length; i++) {
            Knot a = knots[i - 1], b = knots[i];
            point(pose, out, a.center.subtract(a.side), a.u, 1, a.alpha, tint);
            point(pose, out, b.center.subtract(b.side), b.u, 1, b.alpha, tint);
            point(pose, out, b.center.add(b.side), b.u, 0, b.alpha, tint);
            point(pose, out, a.center.add(a.side), a.u, 0, a.alpha, tint);
        }
    }

    static void aimRibbon(PoseStack pose,MultiBufferSource buffers,Knot[] knots) {
        var out=buffers.getBuffer(layer(AIM));
        // Use the bright middle cross-section of the artwork as a continuous beam.
        // Stretching the entire curved sprite over the trajectory displaced its apparent center.
        for(int i=1;i<knots.length;i++) {
            Knot a=knots[i-1],b=knots[i];
            point(pose,out,a.center.subtract(a.side),.5f,.85f,a.alpha,0xffffff);
            point(pose,out,b.center.subtract(b.side),.5f,.85f,b.alpha,0xffffff);
            point(pose,out,b.center.add(b.side),.5f,.25f,b.alpha,0xffffff);
            point(pose,out,a.center.add(a.side),.5f,.25f,a.alpha,0xffffff);
        }
    }

    static void sprite(PoseStack pose, MultiBufferSource buffers, ResourceLocation texture,
                       Vec3 center, Vec3 right, Vec3 up, float alpha, int tint) {
        ribbon(pose, buffers, texture, new Knot[]{
                new Knot(center.subtract(right), up, 0, alpha),
                new Knot(center.add(right), up, 1, alpha)
        }, tint);
    }

    static Vec3 facingSide(Vec3 tangent, Vec3 facing) {
        Vec3 side = tangent.cross(facing).normalize();
        if (side.lengthSqr() < 0.01) side = tangent.cross(new Vec3(0, 1, 0)).normalize();
        if (side.lengthSqr() < 0.01) side = tangent.cross(new Vec3(1, 0, 0)).normalize();
        return side;
    }

    private static void point(PoseStack p, VertexConsumer out, Vec3 point, float u, float v, float alpha, int tint) {
        out.addVertex(p.last(), (float) point.x, (float) point.y, (float) point.z)
                .setColor(tint >> 16 & 255, tint >> 8 & 255, tint & 255, Math.round(255 * Math.clamp(alpha, 0, 1)))
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xf000f0).setNormal(p.last(), 0, 1, 0);
    }
}
