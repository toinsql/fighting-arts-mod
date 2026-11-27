package fightingarts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import fightingarts.StyleService;
import fightingarts.StyleState;
import java.util.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Deterministic storm/soot history: late observers see the same strikes and the same residual marks. */
final class AwakeningStorm {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final ResourceLocation CLOUD=ResourceLocation.fromNamespaceAndPath("fightingarts","textures/effect/awakening_clouds.png");
    private static final RenderType CLOUD_LAYER=RenderType.entityTranslucent(CLOUD);
    private static final List<Scene> SCENES=new ArrayList<>();
    private static ClientLevel world;
    private AwakeningStorm() {}

    static void clear(){SCENES.clear();ScorchDecals.clear();world=MC.level;}

    record Strike(double start,double x,double z,double sourceX,double sourceZ,double height,long seed,int color) {
        boolean active(double age){return age>=start && age<start+.5;}
    }
    static List<Strike> schedule(long seed) {
        List<Strike> strikes=new ArrayList<>();Random random=new Random(seed);
        double[] clocks={1.5,2.05,2.60,3,3,3,3,3,3,3,3,3.5,3.66,4};
        for(int i=0;i<clocks.length;i++) {
            double angle=random.nextDouble()*Math.TAU,radius=3+Math.sqrt(random.nextDouble())*7.5;
            double x=Math.cos(angle)*radius,z=Math.sin(angle)*radius;
            strikes.add(new Strike(clocks[i],x,z,x+random.nextDouble(-1.4,1.4),z+random.nextDouble(-1.4,1.4),
                    6.7+random.nextDouble()*.7,random.nextLong(),i%2));
        }
        return strikes;
    }

    static double cloudOpacity(double age) {
        return AwakeningEffects.ease(age/1.46)*(1-AwakeningEffects.ease((age-4)/1.4));
    }

    static void observe(StyleState state,AwakeningEffects.Field field,float heading) {
        if(world!=MC.level)clear();
        if(!state.phase().equals(StyleService.AWAKENING))return;
        for(Scene s:SCENES)if(s.player.equals(state.playerId()) && s.start==state.phaseStart())return;
        if(SCENES.size()>=24)SCENES.removeFirst();
        SCENES.add(new Scene(state.playerId(),state.phaseStart(),field,heading));
    }

    static void render(PoseStack pose,MultiBufferSource.BufferSource buffers,Camera camera,float partial) {
        if(world!=MC.level){clear();return;}
        double now=MC.level.getGameTime()+partial;
        SCENES.removeIf(s->(now-s.start)/20>21);
        for(Scene scene:SCENES) {
            double age=(now-scene.start)/20;
            StyleState state=AnimationRenderer.STATES.get(scene.player);
            if((state==null || !state.phase().equals(StyleService.AWAKENING) || state.phaseStart()!=scene.start)
                    && age<6.5 && !Double.isFinite(scene.cancel))scene.cancel=age;
            if(scene.base.distanceToSqr(camera.getPosition())>150*150)continue;
            double cancelled=Double.isFinite(scene.cancel)?1-AwakeningEffects.ease((age-scene.cancel)/.3):1;
            for(Burn burn:scene.burns) {
                double end=Math.min(burn.end,scene.cancel);
                if(age<burn.born || end<burn.born)continue;
                double sample=Math.min(Math.min(age,end),3.12);
                double radius=burn.width;
                if(burn.flame!=null) {
                    var f=burn.flame;
                    double power=AwakeningEffects.fireEnvelope(sample,f.front()>.35)
                            *AwakeningEffects.ease((sample-f.delay()-1.46)/.22);
                    if(f.front()>.35 && sample<3)power*=.22;
                    radius=.08+burn.width*power;
                }
                float alpha=(float)(burn.opacity*AwakeningEffects.ease((age-burn.born)/.13)*ScorchDecals.remainingOpacity(age,end));
                if(alpha>0 && radius>.01) {
                    if(burn.faces==null)burn.faces=ScorchDecals.project(burn.point,burn.maximum);
                    ScorchDecals.render(pose,buffers,burn.faces,burn.point,radius,alpha);
                }
            }
            if(age<5.4 && cancelled>0)clouds(pose,buffers,scene,camera,age,cancelled);
            for(Bolt bolt:scene.bolts) {
                if(bolt.strike.start>scene.cancel || !bolt.strike.active(age))continue;
                lightning(pose,buffers,camera,scene,bolt,age-bolt.strike.start);
            }
        }
        buffers.endBatch(ScorchDecals.LAYER);
        buffers.endBatch(CLOUD_LAYER);
        buffers.endBatch(SkillMesh.layer(SkillMesh.EMERALD));
        buffers.endBatch(SkillMesh.layer(SkillMesh.LIME));
        buffers.endBatch(SkillMesh.layer(SkillMesh.WIND));
    }

    private static void clouds(PoseStack pose,MultiBufferSource buffers,Scene scene,Camera camera,double age,double cancelled) {
        Vec3 facing=new Vec3(camera.rotation().transform(new Vector3f(1,0,0))).multiply(1,0,1).normalize();
        double dissolve=AwakeningEffects.ease((age-4)/1.4);
        double flash=0;
        for(Bolt bolt:scene.bolts)if(bolt.strike.active(age))flash=Math.max(flash,.16*(1-(age-bolt.strike.start)/.5));
        for(int i=0;i<scene.puffs.size();i++) {
            Puff puff=scene.puffs.get(i);
            double formation=AwakeningEffects.ease((age-puff.delay)/.8);
            double alpha=formation*cloudOpacity(age)*cancelled;
            if(alpha<.004)continue;
            double spread=(.45+.55*formation)*(1+dissolve*.18);
            Vec3 center=scene.base.add(scene.forward.scale(-3)).add(puff.x*spread,
                    puff.y+Math.sin(age*.65+puff.phase)*.10+dissolve*1.2,puff.z*spread);
            double width=puff.width*(.7+.3*formation),height=puff.height;
            float shade=(float)(.64+flash);
            cloudQuad(pose,buffers,i%4,center,facing.scale(width/2),new Vec3(0,height/2,0),(float)(alpha*.88),shade);
            // Staggered horizontal underside patches join the lobes into a canopy instead of a backdrop.
            if(i%2==0)cloudQuad(pose,buffers,(i+1)%4,center.add(0,-.3,0),new Vec3(width*.55,0,0),
                    new Vec3(0,0,width*.46),(float)(alpha*.76),shade*.8f);
        }
    }

    private static void cloudQuad(PoseStack pose,MultiBufferSource buffers,int cell,Vec3 p,Vec3 right,Vec3 up,float alpha,float shade) {
        var out=buffers.getBuffer(CLOUD_LAYER);
        Vec3[] corners={p.subtract(right).subtract(up),p.add(right).subtract(up),p.add(right).add(up),p.subtract(right).add(up)};
        for(int i=0;i<4;i++) {
            Vec3 v=corners[i];
            out.addVertex(pose.last(),(float)v.x,(float)v.y,(float)v.z).setColor(shade,shade,shade,alpha)
                    .setUv(cell%2*.5f+(i==1||i==2?.5f:0),cell/2*.5f+(i<2?.5f:0))
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xf000f0).setNormal(pose.last(),0,1,0);
        }
    }

    static Vec3[] boltPath(Vec3 source,Vec3 target,long seed,double age) {
        double length=source.distanceTo(target);
        int count=length>3?19:8;Vec3[] path=new Vec3[count];
        double sway=Math.clamp(length*.075,.035,.5);
        double frame=age*24;int key=(int)Math.floor(frame);double mix=AwakeningEffects.ease(frame-key);
        Random a=new Random(seed+key*937L),b=new Random(seed+(key+1)*937L);
        for(int i=0;i<count;i++) {
            double t=i/(double)(count-1),edge=Math.sin(Math.PI*t);
            double x=a.nextDouble(-sway,sway)*(1-mix)+b.nextDouble(-sway,sway)*mix;
            double z=a.nextDouble(-sway,sway)*(1-mix)+b.nextDouble(-sway,sway)*mix;
            path[i]=source.lerp(target,t).add(x*edge,0,z*edge);
        }
        return path;
    }

    private static void lightning(PoseStack pose,MultiBufferSource buffers,Camera camera,Scene scene,Bolt bolt,double age) {
        var strike=bolt.strike;
        Vec3 source=scene.base.add(strike.sourceX,strike.height,strike.sourceZ);
        Vec3[] path=boltPath(source,bolt.ground,strike.seed,age);
        drawBolt(pose,buffers,camera,path,strike.color,.13f,1);
        for(int branch=0;branch<3;branch++) {
            int fork=4+branch*4;
            Vec3 root=path[fork];
            Random random=new Random(strike.seed+branch*113);
            Vec3 end=root.add(random.nextDouble(-1.25,1.25),-random.nextDouble(.7,1.6),random.nextDouble(-1.25,1.25));
            drawBolt(pose,buffers,camera,boltPath(root,end,strike.seed+branch*917,age),strike.color,.06f,.7f);
        }
        if(age<.10) {
            Vec3 right=new Vec3(camera.rotation().transform(new Vector3f(1,0,0))).scale(.27);
            Vec3 up=new Vec3(camera.rotation().transform(new Vector3f(0,1,0))).scale(.34);
            AwakeningEffects.cell(pose,buffers,15,bolt.ground.add(0,.2,0),right,up,(float)(1-age/.1));
        }
    }

    private static void drawBolt(PoseStack pose,MultiBufferSource buffers,Camera camera,Vec3[] path,int color,float width,float alpha) {
        for(int layer=0;layer<3;layer++) {
            SkillMesh.Knot[] knots=new SkillMesh.Knot[path.length];
            for(int i=0;i<path.length;i++) {
                Vec3 tangent=path[Math.min(i+1,path.length-1)].subtract(path[Math.max(0,i-1)]);
                Vec3 side=SkillMesh.facingSide(tangent,path[i].subtract(camera.getPosition()));
                double w=width*(layer==0?1:layer==1?.38:.13);
                float opacity=alpha*(layer==0?.60f:1);
                // Constant center-column UVs keep the entire hot core visible, including both endpoints.
                knots[i]=new SkillMesh.Knot(path[i],side.scale(w),layer==0?i/(float)(path.length-1):.5f,opacity);
            }
            var texture=layer==0?(color==0?SkillMesh.EMERALD:SkillMesh.LIME):SkillMesh.WIND;
            int tint=layer==0?0xffffff:layer==1?(color==0?0xbbffd9:0xb8e48c):0xffffff;
            SkillMesh.ribbon(pose,buffers,texture,knots,tint);
        }
    }

    private record Puff(double x,double z,double y,double width,double height,double phase,double delay) {}
    private record Bolt(Strike strike,Vec3 ground) {}
    private static final class Burn {
        final Vec3 point;final double born,end,width,maximum,opacity;
        final AwakeningEffects.Flame flame;
        List<ScorchDecals.Face> faces;
        Burn(Vec3 point,double born,double end,double width,double maximum,double opacity,AwakeningEffects.Flame flame) {
            this.point=point;this.born=born;this.end=end;this.width=width;this.maximum=maximum;this.opacity=opacity;this.flame=flame;
        }
    }
    private static final class Scene {
        final UUID player;final long start;final Vec3 base,forward;
        final List<Puff> puffs=new ArrayList<>();final List<Bolt> bolts=new ArrayList<>();final List<Burn> burns=new ArrayList<>();
        double cancel=Double.POSITIVE_INFINITY;
        Scene(UUID player,long start,AwakeningEffects.Field field,float heading) {
            this.player=player;this.start=start;this.base=field.origin;this.forward=Vec3.directionFromRotation(0,heading);
            Random random=new Random(player.getLeastSignificantBits());
            for(int i=0;i<35;i++) {
                double angle=i*2.39996,radius=i==0?0:Math.sqrt(random.nextDouble())*11;
                puffs.add(new Puff(Math.cos(angle)*radius,Math.sin(angle)*radius,6.8+random.nextDouble()*1.1,
                        6+random.nextDouble()*2.5,3.8+random.nextDouble()*.9,random.nextDouble()*Math.TAU,random.nextDouble()*.65));
            }
            for(var f:field.flames) {
                boolean front=f.front()>.35;
                double born=front && (f.frontDistance()>2 || Math.abs(f.side())<.65)?3:1.46+f.delay();
                double width=f.width()*1.10;
                burns.add(new Burn(f.base(),born,front?3.75:5,width,width*2.15+.1,.065,f));
            }
            for(int i=0;i<9;i++) {
                double a=i*2.39996;
                burns.add(new Burn(base.add(Math.cos(a)*(.2+i*.055),.025,Math.sin(a)*(.2+i*.055)),
                        1.46,5,.55,.55,.09,null));
            }
            for(Strike strike:schedule(player.getLeastSignificantBits())) {
                Vec3 p=base.add(strike.x,0,strike.z);
                var hit=MC.level.clip(new ClipContext(p.add(0,3,0),p.add(0,-6,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,MC.player));
                if(hit.getType()==HitResult.Type.MISS)continue;
                Vec3 ground=hit.getLocation().add(0,.005,0);
                bolts.add(new Bolt(strike,ground));
                double radius=1.0+random.nextDouble()*.35;
                burns.add(new Burn(ground,strike.start,strike.start+.5,radius,radius,.38,null));
            }
        }
    }
}
