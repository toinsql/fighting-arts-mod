package fightingarts.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

final class DecalGeometry {
    record Vertex(Vec3 position, double u, double v) {
        Vertex lerp(Vertex other,double t) {return new Vertex(position.lerp(other.position,t),u+(other.u-u)*t,v+(other.v-v)*t);}
    }
    static List<Vertex> clip(List<Vertex> input, ToDoubleFunction<Vertex> distance) {
        List<Vertex> out=new ArrayList<>();
        if(input.isEmpty())return out;
        Vertex previous=input.getLast();double before=distance.applyAsDouble(previous);
        for(Vertex current:input) {
            double after=distance.applyAsDouble(current);
            if((before>=0)!=(after>=0))out.add(previous.lerp(current,before/(before-after)));
            if(after>=0)out.add(current);
            previous=current;before=after;
        }
        return out;
    }
    static List<Vertex> textureBounds(List<Vertex> p) {
        p=clip(p,a->a.u);p=clip(p,a->1-a.u);p=clip(p,a->a.v);return clip(p,a->1-a.v);
    }
    static void emit(VertexConsumer out,List<Vertex> polygon,float alpha,Vec3 normal) {
        for(int i=1;i+1<polygon.size();i++)for(Vertex vertex:new Vertex[]{polygon.getFirst(),polygon.get(i),polygon.get(i+1),polygon.get(i+1)}) {
            Vec3 p=vertex.position.add(normal.scale(.003));
            out.addVertex((float)p.x,(float)p.y,(float)p.z).setColor(255,255,255,Math.round(255*alpha))
                    .setUv((float)vertex.u,(float)vertex.v)
                    .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xf000f0)
                    .setNormal((float)normal.x,(float)normal.y,(float)normal.z);
        }
    }
}
