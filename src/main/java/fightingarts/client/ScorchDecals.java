package fightingarts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Soft circular soot projected onto collision tops. Color-only depth writes prevent coplanar flicker. */
final class ScorchDecals {
    static final double HOLD_SECONDS = 15;
    static final double FADE_SECONDS = .75;
    private static final ResourceLocation SOOT=ResourceLocation.fromNamespaceAndPath("fightingarts","textures/effect/awakening_soot.png");
    static final RenderType LAYER = RenderType.entityShadow(SOOT);
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Map<BlockPos,Cached> SURFACES = new HashMap<>();
    private ScorchDecals() {}

    record Face(BlockPos block,BlockState state,double x1,double z1,double x2,double z2,double y) {}
    private record Cached(BlockState state,List<Face> faces) {}
    static void clear(){SURFACES.clear();}

    static double remainingOpacity(double now,double extinguished) {
        return 1-AwakeningEffects.ease((now-extinguished-HOLD_SECONDS)/FADE_SECONDS);
    }

    static List<Face> project(Vec3 center,double radius) {
        List<Face> faces=new ArrayList<>();
        if(SURFACES.size()>8192)SURFACES.clear();
        for(int x=(int)Math.floor(center.x-radius);x<=Math.floor(center.x+radius);x++)
            for(int z=(int)Math.floor(center.z-radius);z<=Math.floor(center.z+radius);z++)
                for(int y=(int)Math.floor(center.y)-3;y<=Math.floor(center.y)+1;y++) {
                    BlockPos pos=new BlockPos(x,y,z);
                    BlockState state=MC.level.getBlockState(pos);
                    Cached cached=SURFACES.get(pos);
                    if(cached==null || cached.state!=state) {
                        List<Face> tops=new ArrayList<>();
                        for(var box:state.getCollisionShape(MC.level,pos).toAabbs()) {
                            double top=y+box.maxY;
                            if(top>center.y+1.1 || top<center.y-3.1)continue;
                            tops.add(new Face(pos,state,x+box.minX,z+box.minZ,x+box.maxX,z+box.maxZ,top));
                        }
                        cached=new Cached(state,tops);SURFACES.put(pos,cached);
                    }
                    faces.addAll(cached.faces);
                }
        return faces;
    }

    static void render(PoseStack pose,MultiBufferSource buffers,List<Face> faces,Vec3 center,double radius,float alpha) {
        if(radius<.015 || alpha<.002)return;
        var out=buffers.getBuffer(LAYER);
        for(Face f:faces) {
            // A mined surface stops receiving the mark immediately; nothing floats over the resulting hole.
            if(MC.level.getBlockState(f.block)!=f.state)continue;
            double x1=Math.max(f.x1,center.x-radius),x2=Math.min(f.x2,center.x+radius);
            double z1=Math.max(f.z1,center.z-radius),z2=Math.min(f.z2,center.z+radius);
            if(x2<=x1 || z2<=z1)continue;
            // Shadow shader multiplies by vertex alpha and preserves the underlying block texture.
            double[][] corners={{x1,z1},{x1,z2},{x2,z2},{x2,z1}};
            for(double[] p:corners)out.addVertex(pose.last(),(float)p[0],(float)(f.y+.004),(float)p[1])
                    .setColor(1f,1f,1f,alpha)
                    .setUv((float)((p[0]-center.x)/(2*radius)+.5),(float)((p[1]-center.z)/(2*radius)+.5))
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xf000f0).setNormal(pose.last(),0,1,0);
        }
    }
}
