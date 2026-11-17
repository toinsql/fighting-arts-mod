package fightingarts.client;

import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Project small texture patches onto each encountered collision face, sharing continuous UVs. */
final class MoonSurface {
    record Patch(List<DecalGeometry.Vertex> vertices,Vec3 normal) {}
    static List<Patch> project(Vec3 eye,Vec3 end) {
        var mc=Minecraft.getInstance();List<Patch> patches=new ArrayList<>();
        Vec3 forward=end.subtract(eye).normalize(),right=forward.cross(new Vec3(0,1,0)).normalize();
        Vec3 up=right.cross(forward).normalize();
        Vec3 axis=right.scale(.87).subtract(up.scale(.49)),across=right.scale(.49).add(up.scale(.87));
        double reach=eye.distanceTo(end);
        // Fixed projection volume. A nearby foreground block must not magnify the
        // same UV patch on a wall behind it.
        double plane=reach;
        for(int x=0;x<64;x++)for(int y=0;y<12;y++) {
            double u=(x+.5)/64.0,v=(y+.5)/12.0;
            List<net.minecraft.world.phys.BlockHitResult> faces=new ArrayList<>();
            java.util.Set<String> seen=new java.util.HashSet<>();
            for(double[] sample:new double[][]{{u,v},{x/64.,y/12.},{(x+1)/64.,y/12.},{(x+1)/64.,(y+1)/12.},{x/64.,(y+1)/12.}}) {
                Vec3 ray=forward.scale(plane).add(axis.scale((sample[0]-.5)*3.6)).add(across.scale((.5-sample[1])*.65)).normalize();
                var face=mc.level.clip(new ClipContext(eye,eye.add(ray.scale(reach)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
                if(face.getType()!=HitResult.Type.MISS && seen.add(face.getBlockPos()+":"+face.getDirection()))faces.add(face);
            }
            for(var hit:faces) {
            Vec3 normal=Vec3.atLowerCornerOf(hit.getDirection().getNormal());
            var shape=mc.level.getBlockState(hit.getBlockPos()).getCollisionShape(mc.level,hit.getBlockPos());
            for(var local:shape.toAabbs()) {
                var box=local.move(hit.getBlockPos());
                if(!box.inflate(.001).contains(hit.getLocation()))continue;
                List<DecalGeometry.Vertex> polygon=new ArrayList<>();
                for(int[] corner:new int[][]{{0,0},{1,0},{1,1},{0,1}}) {
                    double cu=(x+corner[0])/64.0,cv=(y+corner[1])/12.0;
                    Vec3 ray=forward.scale(plane).add(axis.scale((cu-.5)*3.6)).add(across.scale((.5-cv)*.65)).normalize();
                    double denominator=ray.dot(normal);
                    if(Math.abs(denominator)<.001)continue;
                    double distance=hit.getLocation().subtract(eye).dot(normal)/denominator;
                    if(distance<0 || distance>reach+.08)continue;
                    Vec3 p=eye.add(ray.scale(distance));
                    polygon.add(new DecalGeometry.Vertex(p,cu,cv));
                }
                if (polygon.size() != 4) continue;
                polygon=DecalGeometry.clip(polygon,p->p.position().x-box.minX+.00001);
                polygon=DecalGeometry.clip(polygon,p->box.maxX-p.position().x+.00001);
                polygon=DecalGeometry.clip(polygon,p->p.position().y-box.minY+.00001);
                polygon=DecalGeometry.clip(polygon,p->box.maxY-p.position().y+.00001);
                polygon=DecalGeometry.clip(polygon,p->p.position().z-box.minZ+.00001);
                polygon=DecalGeometry.clip(polygon,p->box.maxZ-p.position().z+.00001);
                if(polygon.size()>=3)patches.add(new Patch(polygon,normal));
            }
        }
        }
        return patches;
    }
    static void render(PoseStack pose,MultiBufferSource buffers,List<Patch> patches,float alpha,float reveal) {
        var out=buffers.getBuffer(SkillMesh.layer(SkillMesh.FISSURE));
        for(var patch:patches) {
            var clipped=DecalGeometry.clip(patch.vertices,p->reveal-p.u());
            List<DecalGeometry.Vertex> transformed=new ArrayList<>();
            for(var v:clipped) {
                Vec3 p=v.position().add(patch.normal.scale(.012));
                Vector3f pos=pose.last().pose().transformPosition((float)p.x,(float)p.y,(float)p.z,new Vector3f());
                transformed.add(new DecalGeometry.Vertex(new Vec3(pos),v.u(),v.v()));
            }
            DecalGeometry.emit(out,transformed,alpha,patch.normal);
        }
    }
}
