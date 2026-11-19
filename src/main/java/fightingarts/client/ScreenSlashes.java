package fightingarts.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

/** Three simultaneous blade sweeps, with curved wakes that keep drifting as they dissolve. */
final class ScreenSlashes {
    private ScreenSlashes() {}

    static SkillMesh.Knot[] path(double width, double height, double age, int blade) {
        double head = Math.clamp(age / .14, 0, 1);
        double tail = Math.clamp((age - .55) / .65, 0, 1) * .22;
        float fade = (float)Math.pow(1 - Math.clamp((age - 1) / .2, 0, 1), 1.5);
        double span = Math.hypot(width, height) * 1.12;
        SkillMesh.Knot[] knots = new SkillMesh.Knot[65];
        for (int i = 0; i < knots.length; i++) {
            float u = i / (float)(knots.length - 1);
            double t = tail + (head - tail) * u;
            double bend = Math.sin(t * Math.PI) * height * (.065 + .018 * Math.sin(age * 7 + blade));
            double ripple = Math.sin(t * 18 - age * 15 + blade) * height * .003 * Math.sin(t * Math.PI);
            Vec3 center = new Vec3((t - .5) * span, bend + ripple + age * height * (blade - 1) * .035, 0);
            double slope = (Math.cos(t * Math.PI) * height * .065 * Math.PI) / span;
            Vec3 side = new Vec3(-slope, 1, 0).normalize();
            double taper = Math.pow(Math.max(0, Math.sin(u * Math.PI)), .7);
            double thickness = height * .065 * taper * (1 - .45 * Math.clamp(age, 0, 1));
            knots[i] = new SkillMesh.Knot(center, side.scale(thickness), u, fade);
        }
        return knots;
    }

    static void render(GuiGraphics graphics, double age) {
        if (age < 0 || age >= 1.2) return;
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        var pose = graphics.pose();
        for (int blade = 0; blade < 3; blade++) {
            float angle = blade == 0 ? -29 : blade == 1 ? 29 : -2;
            angle += (float)(age * (blade - 1) * 5);
            pose.pushPose();
            pose.translate(graphics.guiWidth() * .5, graphics.guiHeight() * (.47 + blade * .025), 200);
            pose.mulPose(Axis.ZP.rotationDegrees(angle));
            SkillMesh.Knot[] wake = path(graphics.guiWidth(), graphics.guiHeight(), age, blade);
            draw(pose, SkillMesh.WIND, wake, 0xc6a1ff);
            SkillMesh.Knot[] edge = new SkillMesh.Knot[wake.length];
            for (int i = 0; i < edge.length; i++) {
                var knot = wake[i];
                edge[i] = new SkillMesh.Knot(knot.center(), knot.side().scale(.20), knot.u(), knot.alpha());
            }
            draw(pose, SkillMesh.VIOLET, edge, 0xffffff);
            pose.popPose();
        }
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    private static void draw(com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.resources.ResourceLocation texture,
                             SkillMesh.Knot[] knots,int tint) {
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0,texture);
        var out=com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR);
        for(int i=1;i<knots.length;i++) {
            var a=knots[i-1];var b=knots[i];
            vertex(out,pose,a.center().subtract(a.side()),a.u(),1,a.alpha(),tint);
            vertex(out,pose,b.center().subtract(b.side()),b.u(),1,b.alpha(),tint);
            vertex(out,pose,b.center().add(b.side()),b.u(),0,b.alpha(),tint);
            vertex(out,pose,a.center().add(a.side()),a.u(),0,a.alpha(),tint);
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(out.buildOrThrow());
    }
    private static void vertex(com.mojang.blaze3d.vertex.BufferBuilder out,com.mojang.blaze3d.vertex.PoseStack pose,
                               Vec3 point,float u,float v,float alpha,int tint) {
        out.addVertex(pose.last().pose(),(float)point.x,(float)point.y,(float)point.z).setUv(u,v)
                .setColor(tint>>16&255,tint>>8&255,tint&255,Math.round(alpha*255));
    }
}
