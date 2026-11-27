package fightingarts.client;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fightingarts.DragonFlight;
import fightingarts.SantoryuSkills;
import java.util.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

final class RiftDragon {
    private static final Minecraft MC=Minecraft.getInstance();
    private static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath("fightingarts",path);}
    private static ResourceLocation texture(String name){return id("textures/effect/"+name+".png");}
    private static final ResourceLocation SKIN=texture("rift_scales"), FLASH=texture("rift_impact"),
            MIST=texture("rift_mist"), EMBERS=texture("rift_embers"), LIGHTNING=texture("rush_lightning");
    private static final Map<Integer,Flight> flights=new HashMap<>();
    private static List<List<ModelPart.Cube>> bones;
    private static Object resources;
    private static final class Flight {
        final Vec3 start,end;
        final long born;
        double contact=Double.POSITIVE_INFINITY;
        Flight(Vec3 start,Vec3 end,long born){this.start=start;this.end=end;this.born=born;}
    }
    static void clear(){flights.clear();}
    static void event(SantoryuSkills.Effect fx){
        if(fx.kind()==SantoryuSkills.DRAGON){
            flights.put(fx.target(),new Flight(fx.from(),fx.to(),MC.level.getGameTime()));return;
        }
        Flight f=flights.get(fx.target());
        if(f==null)return;
        // Match the actual intersection to a sub-tick on the descending flight path.
        double best=Double.MAX_VALUE;
        for(double t=.125;t<=DragonFlight.duration(f.start,f.end);t+=.125){
            double error=DragonFlight.point(f.start,f.end,t).distanceToSqr(fx.from());
            if(error<best){best=error;f.contact=t;}
        }
    }
    private static float number(JsonObject cube,String key,int i){return cube.getAsJsonArray(key).get(i).getAsFloat();}
    private static List<List<ModelPart.Cube>> model(){
        if(bones!=null && resources==MC.getResourceManager())return bones;
        resources=MC.getResourceManager();
        var result=new ArrayList<List<ModelPart.Cube>>();
        try(var reader=MC.getResourceManager().openAsReader(id("styles/rift_dragon.geo.json"))){
            var geo=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            for(var item:geo.getAsJsonArray("bones")){
                var bone=item.getAsJsonObject();
                var cubes=new ArrayList<ModelPart.Cube>();
                for(var entry:bone.getAsJsonArray("cubes")){
                    var c=entry.getAsJsonObject();
                    cubes.add(new ModelPart.Cube((int)number(c,"uv",0),(int)number(c,"uv",1),
                        number(c,"origin",0)-number(bone,"pivot",0),
                        number(c,"origin",1)-number(bone,"pivot",1),
                        number(c,"origin",2)-number(bone,"pivot",2),
                        number(c,"size",0),number(c,"size",1),number(c,"size",2),
                        0,0,0,false,64,64,EnumSet.allOf(Direction.class)));
                }
                result.add(cubes);
            }
        }catch(java.io.IOException e){throw new IllegalStateException("Cannot load rift dragon",e);}
        return bones=result;
    }
    private static Vec3 spine(Flight f,double t){
        return DragonFlight.point(f.start,f.end,t);
    }
    static void render(PoseStack pose,MultiBufferSource buffers,Camera camera,double now){
        flights.values().removeIf(f->now-f.born>DragonFlight.duration(f.start,f.end)+27);
        if(flights.isEmpty())return;
        var parts=model();
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(SKIN));
        var wisps=new ArrayList<Vec3>();
        var wakes=new ArrayList<SkillMesh.Knot[]>();
        for(var f:flights.values()){
            double age=now-f.born, t=age;
            double stop=Math.min(f.contact,DragonFlight.duration(f.start,f.end));
            var wake=new ArrayList<SkillMesh.Knot>();
            for(int i=0;i<parts.size();i++){
                if(t<0)break;
                double sample=Math.min(t,stop);
                Vec3 tangent=spine(f,sample).subtract(spine(f,Math.max(0,sample-.15))).normalize();
                double speedAtContact=spine(f,stop).distanceTo(spine(f,stop-1));
                double penetration=Math.max(0,t-stop)*speedAtContact;
                if(penetration<.76){
                    Vec3 pos=spine(f,sample).add(tangent.scale(penetration));
                    Vec3 across=tangent.cross(new Vec3(0,1,0)).normalize();
                    double twist=age*.36-i*.72;
                    Vec3 crest=pos.add(across.scale(.67*Math.cos(twist))).add(0,.35+.49*Math.sin(twist),0);
                    wake.add(new SkillMesh.Knot(crest,SkillMesh.facingSide(tangent,camera.getPosition().subtract(crest)).scale(.13),
                            i/(float)(parts.size()-1),.75f));
                    if(i%2==0 && penetration==0){
                        double phase=age*.32+i*1.6;
                        Vec3 side=tangent.cross(new Vec3(0,1,0)).normalize();
                        wisps.add(pos.add(side.scale(Math.cos(phase)*.68)).add(0,Math.sin(phase)*.54,0));
                    }
                    pose.pushPose();pose.translate(pos.x,pos.y,pos.z);
                    pose.mulPose(Axis.YP.rotation((float)Math.atan2(-tangent.x,-tangent.z)));
                    pose.mulPose(Axis.XP.rotation((float)Math.asin(Math.clamp(tangent.y,-1,1))));
                    pose.mulPose(Axis.ZP.rotation((float)(.14*Math.sin(t*.4))));
                    pose.scale(2.75f,2.75f,2.75f);
                    for(var cube:parts.get(i)){
                        // The lower jaw hinges below the snout, then closes on contact.
                        boolean jaw=i==0 && cube.minY<=-5 && cube.minZ<=-13;
                        if(jaw){
                            pose.pushPose();pose.translate(0,-.21,-.25);
                            pose.mulPose(Axis.XP.rotationDegrees((float)(-21*Math.sin(Math.PI*Math.clamp(t/stop,0,1)))));
                            pose.translate(0,.21,.25);
                        }
                        cube.compile(pose.last(),out,0xf000f0,OverlayTexture.NO_OVERLAY,0xffffffff);
                        if(jaw)pose.popPose();
                    }
                    pose.popPose();
                }
                double speed=spine(f,Math.clamp(t,1,stop)).distanceTo(spine(f,Math.clamp(t,1,stop)-1));
                t-=.86/Math.max(.36,speed);
            }
            if(wake.size()>1)wakes.add(wake.toArray(SkillMesh.Knot[]::new));
        }
        for(var wake:wakes)SkillMesh.ribbon(pose,buffers,SkillMesh.LIME,wake,0xa9e78b);
        Vec3 right=new Vec3(camera.rotation().transform(new Vector3f(1,0,0)));
        Vec3 up=new Vec3(camera.rotation().transform(new Vector3f(0,1,0)));
        for(int i=0;i<wisps.size();i++){
            boolean spark=i%2==0;
            double radius=spark?.28:.36;
            SkillMesh.sprite(pose,buffers,spark?LIGHTNING:MIST,wisps.get(i),right.scale(radius),up.scale(radius),
                    spark?.75f:.42f,0xffffff);
        }
    }
    static void particles(PoseStack pose,MultiBufferSource buffers,Camera cam,SantoryuSkills.Effect fx,double age,long seed){
        boolean rush=fx.kind()==SantoryuSkills.RUSH,impact=fx.kind()==SantoryuSkills.DRAGON_IMPACT;
        boolean hit=fx.kind()==SantoryuSkills.DRAGON_HIT;
        double life=rush?.22:impact?.85:hit?.35:1;
        if(age<0 || age>=life)return;
        double t=age/life;
        var rand=new Random(seed);
        Vec3 right=new Vec3(cam.rotation().transform(new Vector3f(1,0,0)));
        Vec3 up=new Vec3(cam.rotation().transform(new Vector3f(0,1,0)));
        int count=rush?5:impact?20:hit?7:12;
        for(int i=0;i<count;i++){
            double angle=rand.nextDouble()*2*Math.PI, radius=(rush?.42:.65+t*2.8)*(.45+rand.nextDouble());
            Vec3 p=fx.from().add(Math.cos(angle)*radius,(rush?rand.nextDouble()*1.8:1.1+t*rand.nextDouble()*2.2),Math.sin(angle)*radius);
            if(rush)p=p.subtract(fx.to().scale(t*.9));
            double size=rush?.25:impact?.27:.53;
            size*=.75+rand.nextDouble()*.6;
            var tex=rush?LIGHTNING:hit?(i%2==0?LIGHTNING:EMBERS):impact?(i%3==0?FLASH:i%3==1?LIGHTNING:EMBERS):MIST;
            SkillMesh.sprite(pose,buffers,tex,p,right.scale(size),up.scale(size*(rush?1.55:1)),(float)Math.pow(1-t,.8),0xffffff);
        }
        if(impact)SkillMesh.sprite(pose,buffers,FLASH,fx.from(),right.scale(1+t*3),up.scale(1+t*3),(float)Math.pow(1-t,2),0xffffff);
    }
    static void flush(MultiBufferSource.BufferSource buffers){
        buffers.endBatch(RenderType.entityCutoutNoCull(SKIN));
        for(var texture:List.of(FLASH,MIST,EMBERS,LIGHTNING))buffers.endBatch(SkillMesh.layer(texture));
    }
}
