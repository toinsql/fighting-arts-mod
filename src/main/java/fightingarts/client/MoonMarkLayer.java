package fightingarts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.*;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Reuses the animated model vertices: the mark stays on skin as limbs and body move. */
public final class MoonMarkLayer<T extends LivingEntity,M extends EntityModel<T>> extends RenderLayer<T,M> {
    private static final Map<LivingEntity,Mark> MARKS=new WeakHashMap<>();
    private MoonMarkLayer(LivingEntityRenderer<T,M> parent){super(parent);}
    @SuppressWarnings({"rawtypes","unchecked"})
    public static void register(EntityRenderersEvent.AddLayers event) {
        for(var type:event.getEntityTypes())if(event.getRenderer(type) instanceof LivingEntityRenderer renderer)
            renderer.addLayer(new MoonMarkLayer(renderer));
        for(var skin:event.getSkins())if(event.getSkin(skin) instanceof LivingEntityRenderer renderer)
            renderer.addLayer(new MoonMarkLayer(renderer));
    }
    static void add(LivingEntity entity,Vec3 incoming){MARKS.put(entity,new Mark(entity.level().getGameTime(),incoming));}
    static void clear(){MARKS.clear();}
    @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,T entity,float swing,float amount,
                                 float partial,float age,float yaw,float pitch) {
        Mark mark=MARKS.get(entity);if(mark==null || entity.isInvisible())return;
        double time=entity.level().getGameTime()+partial-mark.start;
        if(time>=120 || !entity.isAlive()){MARKS.remove(entity);return;}
        float alpha=(float)Math.min(1,(120-time)/10);
        // The inverse root matrix removes camera and body orientation before the initial UV projection.
        Matrix4f inverse=new Matrix4f(pose.last().pose()).invert();
        Vec3 facing=mark.incoming.scale(-1).yRot((float)Math.toRadians(entity.yBodyRot));
        Vec3 right=new Vec3(facing.z,0,-facing.x).normalize();
        double width=Math.clamp(entity.getBbWidth()*1.6,.7,2.5);
        var collector=new SkinConsumer(buffers.getBuffer(SkillMesh.layer(SkillMesh.FISSURE)),inverse,mark,
                right,facing,width,1.5-entity.getBbHeight()*.55,alpha,(float)Math.min(1,time/5));
        getParentModel().renderToBuffer(pose,collector,light,0,-1);
        collector.finish();
    }
    private static final class Mark {
        final long start;final Vec3 incoming;final List<double[]> uv=new ArrayList<>();
        Mark(long start,Vec3 incoming){this.start=start;this.incoming=incoming;}
    }
    private static final class SkinConsumer implements VertexConsumer {
        final VertexConsumer out;final Matrix4f inverse;final Mark mark;final Vec3 right,facing;
        final double width,centerY;final float alpha,reveal;int index;Vec3 position,normal=Vec3.ZERO;
        final List<DecalGeometry.Vertex> quad=new ArrayList<>();
        SkinConsumer(VertexConsumer out,Matrix4f inverse,Mark mark,Vec3 right,Vec3 facing,double width,double centerY,float alpha,float reveal) {
            this.out=out;this.inverse=inverse;this.mark=mark;this.right=right;this.facing=facing;
            this.width=width;this.centerY=centerY;this.alpha=alpha;this.reveal=reveal;
        }
        private void flushVertex() {
            if(position==null)return;
            if(index>=mark.uv.size()) {
                Vector3f local=inverse.transformPosition((float)position.x,(float)position.y,(float)position.z,new Vector3f());
                double horizontal=new Vec3(local).dot(right),vertical=local.y-centerY;
                mark.uv.add(new double[]{.5+(.87*horizontal+.49*vertical)/width,
                        .5+(-.49*horizontal+.87*vertical)/(width*.24)});
            }
            double[] uv=mark.uv.get(index++);quad.add(new DecalGeometry.Vertex(position,uv[0],uv[1]));position=null;
            if(quad.size()==4) {
                var polygon=DecalGeometry.textureBounds(quad);
                polygon=DecalGeometry.clip(polygon,p->reveal-p.u());
                DecalGeometry.emit(out,polygon,alpha,normal);
                quad.clear();
            }
        }
        void finish(){flushVertex();}
        public VertexConsumer addVertex(float x,float y,float z){flushVertex();position=new Vec3(x,y,z);return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        public VertexConsumer setUv(float u,float v){return this;}
        public VertexConsumer setUv1(int u,int v){return this;}
        public VertexConsumer setUv2(int u,int v){return this;}
        public VertexConsumer setNormal(float x,float y,float z){normal=new Vec3(x,y,z);return this;}
    }
}
