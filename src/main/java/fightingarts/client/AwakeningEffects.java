package fightingarts.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import fightingarts.StyleMode;
import fightingarts.StyleService;
import fightingarts.StyleState;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3f;

/** One synchronized clock drives the camera, layered sprite animation and nearby spectators' sky. */
public final class AwakeningEffects {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath("fightingarts", "textures/effect/awakening_atlas.png");
    private static final int ATLAS_CELL = 64;
    private static final ResourceLocation SYMBOL = ResourceLocation.fromNamespaceAndPath("fightingarts", "textures/gui/style_symbol.png");
    private static final ResourceLocation AWAKENED_SYMBOL = ResourceLocation.fromNamespaceAndPath("fightingarts", "textures/gui/style_symbol_awakened.png");
    private static final Map<UUID, Field> FIELDS = new HashMap<>();
    private AwakeningEffects() {}

    static void install(IEventBus bus) {
        NeoForge.EVENT_BUS.addListener(AwakeningEffects::render);
        NeoForge.EVENT_BUS.addListener(AwakeningEffects::cameraShake);
        NeoForge.EVENT_BUS.addListener(AwakeningEffects::fog);
        bus.addListener((EntityRenderersEvent.AddLayers event) -> {
            for (var skin : event.getSkins()) {
                net.minecraft.client.renderer.entity.player.PlayerRenderer renderer = event.getSkin(skin);
                if (renderer != null) renderer.addLayer(new Hands(renderer));
            }
        });
    }
    static void clear() { FIELDS.clear(); AwakeningStorm.clear(); }

    public static double ease(double x) { x = Math.clamp(x, 0, 1); return x*x*(3-2*x); }

    public static double shakeAmplitude(double age) {
        if (age < 1.46) return 0;
        if (age < 3) return .45 + .5 * ease((age-1.46)/1.54);
        if (age < 3.25) return 4.4 * (1 - .28 * ease((age-3)/.25));
        if (age < 3.6) return 1.65;
        return 1.65 * (1-ease((age-3.6)/.9));
    }

    public static double fireEnvelope(double age, boolean front) {
        if (age < 1.46 || age >= 5) return 0;
        double growth = ease((age-1.46)/.24);
        double burst = 1 + 1.15 * ease((age-3)/.12);
        double fade = 1-ease((age-4.2)/.8);
        return growth * burst * fade * (front ? 1-ease((age-3.2)/.55) : 1);
    }

    public static double[] camera(float heading, Vec3 position, double age) {
        double radius = 4.6 - .65*ease((age-1.46)/1.54);
        if (age >= 3) radius = 3.95 + 9.55*ease((age-3)/.20) + 1.1*ease((age-3.2)/1.3);
        if (age >= 4.5) radius = 14.6 - .75*ease((age-4.5)/2);
        double height = 1.65 + 2.1*ease((age-3)/.25);
        double angle = Math.toRadians(heading);
        double shake = shakeAmplitude(age);
        double yaw = heading + 180 + shake*Math.sin(age*87.3);
        double pitch = Math.toDegrees(Math.atan2(height-1.05,radius)) + shake*.65*Math.sin(age*109.7);
        return new double[]{position.x-Math.sin(angle)*radius + .018*shake*Math.sin(age*99),
                position.y+height+.014*shake*Math.sin(age*113), position.z+Math.cos(angle)*radius, yaw,pitch};
    }

    private static double age(StyleState state, double partial) {
        return (MC.level.getGameTime()+partial-state.phaseStart())/20;
    }

    private static boolean awakened(StyleState state) {
        return state != null && state.mode() == StyleMode.AWAKENED && !state.phase().equals("OFF") && !state.phase().equals("SHEATHING");
    }

    /** Distance uses the viewer, not the cinematic camera, so a zoom never changes the affected radius. */
    public static double influence(double distance) {
        return 1-ease((distance-25)/5);
    }

    public static Vec3 sky(Vec3 original, float partial) {
        if (MC.level == null || MC.player == null) return original;
        double darkness=0, green=0;
        for (StyleState state : AnimationRenderer.STATES.values()) {
            if (!awakened(state)) continue;
            var caster=MC.level.getPlayerByUUID(state.playerId());
            if (caster==null || !caster.isAlive()) continue;
            double strength=influence(caster.distanceTo(MC.player));
            double time=age(state,partial);
            double ending=state.phase().equals("ACTIVE") ? 1-ease(time) : 1;
            if (state.phase().equals(StyleService.AWAKENING)) {
                darkness=Math.max(darkness,ease(time/1.46)*strength);
                green=Math.max(green,ease((time-3)/.12)*strength);
            } else if (ending>0) {
                darkness=Math.max(darkness,ending*strength);green=Math.max(green,ending*strength);
            }
        }
        Vec3 dark=original.scale(.22);
        Vec3 target=dark.lerp(new Vec3(.055,.28,.115),green);
        return original.lerp(target,darkness*.92);
    }

    private static void fog(ViewportEvent.ComputeFogColor event) {
        Vec3 result=sky(new Vec3(event.getRed(),event.getGreen(),event.getBlue()),(float)event.getPartialTick());
        event.setRed((float)result.x);event.setGreen((float)result.y);event.setBlue((float)result.z);
    }

    private static void cameraShake(ViewportEvent.ComputeCameraAngles event) {
        if (MC.level==null || MC.player==null || StyleClient.local()!=null && StyleClient.local().phase().equals(StyleService.AWAKENING)) return;
        double strength=0,time=0;
        for (StyleState state : AnimationRenderer.STATES.values()) {
            if (!state.phase().equals(StyleService.AWAKENING)) continue;
            var caster=MC.level.getPlayerByUUID(state.playerId());
            if(caster==null)continue;
            double a=age(state,event.getPartialTick());
            if(a<3 || a>4.5)continue;
            double value=shakeAmplitude(a)*influence(caster.distanceTo(MC.player));
            if(value>strength){strength=value;time=a;}
        }
        event.setYaw(event.getYaw()+(float)(strength*Math.sin(time*87.3)));
        event.setPitch(event.getPitch()+(float)(strength*.65*Math.sin(time*109.7)));
        event.setRoll(event.getRoll()+(float)(strength*.35*Math.sin(time*93.1)));
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES || MC.level==null) return;
        float partial=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        FIELDS.keySet().removeIf(id -> !awakened(AnimationRenderer.STATES.get(id)));
        var pose=event.getPoseStack();var buffers=MC.renderBuffers().bufferSource();
        var camera=event.getCamera();Vec3 eye=camera.getPosition();
        pose.pushPose();pose.translate(-eye.x,-eye.y,-eye.z);
        Vec3 right=new Vec3(camera.rotation().transform(new Vector3f(1,0,0)));
        Vec3 up=new Vec3(camera.rotation().transform(new Vector3f(0,1,0)));
        for(StyleState state:AnimationRenderer.STATES.values()) {
            if(!state.phase().equals(StyleService.AWAKENING))continue;
            var player=MC.level.getPlayerByUUID(state.playerId());
            if(player==null || !player.isAlive())continue;
            Field field=FIELDS.get(state.playerId());
            if(field==null || field.start!=state.phaseStart()) {
                field=new Field(state.phaseStart(),player.getPosition(partial),player.getYRot(),state.playerId().hashCode());
                FIELDS.put(state.playerId(),field);
            }
            AwakeningStorm.observe(state,field,player.getYRot());
        }
        AwakeningStorm.render(pose,buffers,camera,partial);
        for (StyleState state : AnimationRenderer.STATES.values()) {
            if(!awakened(state))continue;
            var player=MC.level.getPlayerByUUID(state.playerId());
            if(player==null || !player.isAlive() || player.position().distanceToSqr(eye)>10000)continue;
            Vec3 base=player.getPosition(partial);
            double time=state.phase().equals(StyleService.AWAKENING) ? age(state,partial) : 10+age(state,partial);
            if(time<1.46) {
                motes(pose,buffers,base,time,state.playerId().getLeastSignificantBits(),right,up);
            }
            if(time>=1.46 && time<5) {
                Field field=FIELDS.get(state.playerId());
                if(field==null || field.start!=state.phaseStart()) {
                    field=new Field(state.phaseStart(),base,player.getYRot(),state.playerId().hashCode());
                    FIELDS.put(state.playerId(),field);
                }
                for(int i=0;i<field.flames.size();i++) {
                    Flame f=field.flames.get(i);
                    double local=time-f.delay;
                    boolean front=f.front>.35;
                    double power=fireEnvelope(time,front)*ease((local-1.46)/.22);
                    // Leave the frontal silhouette clear during charging; the eruption fills this opening.
                    if(front && time<3)power*=f.frontDistance>2 ? 0 : .22;
                    if(front && time<3 && Math.abs(f.side)<.65)power=0;
                    if(power<.005)continue;
                    double height=f.height*power*(1+.06*Math.sin(time*8+f.phase));
                    float opacity=(float)(.84*(1-ease((time-4.2)/.8)));
                    flame(pose,buffers,f.base,f.width*power,height,time,f.phase,i%2,opacity);
                    if(i%3==0) {
                        double cycle=(time*.6+f.phase)%1;
                        Vec3 ember=f.base.add(Math.sin(f.phase+time)*.3,height*.5+cycle*1.6,Math.cos(f.phase+time)*.3);
                        cell(pose,buffers,12+i%2,ember,right.scale(.055),up.scale(.085),(float)(power*.42*(1-cycle)));
                    }
                }
                double ground=ease((time-1.46)/.3)*(1-ease((time-4.2)/.8));
                for(int i=0;i<9;i++) {
                    double a=i*2.39996;
                    Vec3 foot=base.add(Math.cos(a)*(.2+i*.055),.025,Math.sin(a)*(.2+i*.055));
                    flame(pose,buffers,foot,.48*ground,.42*ground,time,i*.39,2,(float)(ground*.85));
                }
            }
            if(time>=3 && time<3.85) shockwave(pose,buffers,base,time-3);
            // Body arcs surround the world model. Rendering them through the owner's FP near plane
            // would project the torso into long streaks across the view; the FP hands have their own aura.
            if(time>=3 && !(player==MC.player && MC.options.getCameraType().isFirstPerson()))
                bodyLightning(pose,buffers,base,time,state.playerId().hashCode(),right);
        }
        pose.popPose();
        buffers.endBatch(SkillMesh.layer(ATLAS));
        buffers.endBatch(SkillMesh.layer(SkillMesh.WIND));
        buffers.endBatch(SkillMesh.layer(SkillMesh.EMERALD));
        buffers.endBatch(SkillMesh.layer(SkillMesh.LIME));
    }

    private static void motes(PoseStack pose,MultiBufferSource buffers,Vec3 base,double time,long seed,Vec3 right,Vec3 up) {
        for(int i=0;i<80;i++) {
            double absorb=moteAbsorption(i,time,seed);
            Vec3 p=base.add(moteOffset(i,time,seed));
            double size=(.038+(i%7)*.005)*(1-.55*absorb);
            cell(pose,buffers,12+i%2,p,right.scale(size),up.scale(size*1.3),
                    (float)(ease(time/.18)*(1-ease((absorb-.85)/.15))));
        }
    }

    static double moteAbsorption(int index,double time,long seed) {
        Random random=new Random(seed+index*7919L);
        double start=1.15+random.nextDouble()*.14;
        return ease((time-start)/(1.46-start));
    }

    static Vec3 moteOffset(int index,double time,long seed) {
        Random random=new Random(seed+index*7919L);
        double phase=random.nextDouble()*Math.TAU;
        Vec3 normal=new Vec3(random.nextDouble(-1,1),random.nextDouble(-1,1),random.nextDouble(-1,1)).normalize();
        Vec3 u=normal.cross(new Vec3(0,1,0)).normalize();
        if(u.lengthSqr()<.01)u=new Vec3(1,0,0);
        Vec3 v=normal.cross(u).normalize();
        double absorb=moteAbsorption(index,time,seed);
        double angle=phase+time*(2.1+random.nextDouble()*2)+absorb*1.4;
        double radius=.58+random.nextDouble()*.25;
        Vec3 orbit=u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));
        double x=orbit.x*radius+.07*Math.sin(time*2.4+phase);
        double z=orbit.z*radius+.07*Math.cos(time*1.7+phase);
        double y=Math.max(.08,1.04+orbit.y*1.14+.06*Math.sin(time*2+phase));
        return new Vec3(x,y,z).lerp(new Vec3(0,.98,0),absorb);
    }

    private static void flame(PoseStack pose,MultiBufferSource buffers,Vec3 base,double width,double height,
                              double time,double phase,int row,float alpha) {
        if(height<.005 || alpha<.005)return;
        int frame=Math.floorMod((int)Math.floor(time*10+phase*4),4);
        double angle=phase*2.4;
        // Two intersecting, slightly curved sheets give each tongue depth from every angle.
        for(int plane=0;plane<2;plane++) {
            Vec3 right=new Vec3(Math.cos(angle+plane*Math.PI/2),0,Math.sin(angle+plane*Math.PI/2));
            for(int strip=0;strip<4;strip++) {
                double low=strip/4.0,high=(strip+1)/4.0;
                Vec3 a=base.add(right.scale(Math.sin(low*3+time*5+phase)*width*.11*low)).add(0,height*low,0);
                Vec3 b=base.add(right.scale(Math.sin(high*3+time*5+phase)*width*.11*high)).add(0,height*high,0);
                quad(pose,buffers,row*4+frame,a.subtract(right.scale(width/2)),a.add(right.scale(width/2)),
                        b.add(right.scale(width/2)),b.subtract(right.scale(width/2)),1-low,1-high,alpha);
            }
        }
    }

    private static void shockwave(PoseStack pose,MultiBufferSource buffers,Vec3 base,double age) {
        double radius=20*Math.min(1,age/.8),fade=1-ease((age-.55)/.3);
        if(radius<.05)return;
        for(int band=0;band<2;band++) {
            SkillMesh.Knot[] ring=new SkillMesh.Knot[97];
            for(int i=0;i<ring.length;i++) {
                double a=i*Math.TAU/(ring.length-1);
                Vec3 radial=new Vec3(Math.cos(a),0,Math.sin(a));
                Vec3 p=base.add(radial.scale(radius*(1-band*.035))).add(0,.12+band*.17,0);
                var hit=MC.level.clip(new ClipContext(p.add(0,3,0),p.add(0,-5,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,MC.player));
                if(hit.getType()!=HitResult.Type.MISS)p=new Vec3(p.x,hit.getLocation().y+.075+band*.17,p.z);
                ring[i]=new SkillMesh.Knot(p,radial.scale(.17+.012*radius),i/(float)(ring.length-1),(float)(fade*.8));
            }
            SkillMesh.ribbon(pose,buffers,SkillMesh.WIND,ring,band==0?0x8dffb6:0x83c75e);
        }
    }

    private static void bodyLightning(PoseStack pose,MultiBufferSource buffers,Vec3 base,double time,int seed,Vec3 facing) {
        int cycle=(int)(time*15);
        Random random=new Random(seed+cycle*731L);
        for(int bolt=0;bolt<7;bolt++) {
            double angle=random.nextDouble()*Math.TAU;
            double y=random.nextDouble()*.9;
            SkillMesh.Knot[] knots=new SkillMesh.Knot[8];
            for(int k=0;k<knots.length;k++) {
                double t=k/(double)(knots.length-1),a=angle+t*2.8;
                Vec3 p=base.add(Math.cos(a)*(.32+random.nextDouble()*.13),y+t*.9,Math.sin(a)*(.32+random.nextDouble()*.13));
                Vec3 tangent=new Vec3(-Math.sin(a),1,Math.cos(a));
                Vec3 side=SkillMesh.facingSide(tangent,p.subtract(MC.gameRenderer.getMainCamera().getPosition()));
                knots[k]=new SkillMesh.Knot(p,side.scale(.018*Math.sin(Math.PI*t)),(float)t,.8f);
            }
            SkillMesh.ribbon(pose,buffers,bolt%2==0?SkillMesh.EMERALD:SkillMesh.LIME,knots,0xffffff);
        }
    }

    static void cell(PoseStack pose,MultiBufferSource buffers,int index,Vec3 center,Vec3 right,Vec3 up,float alpha) {
        quad(pose,buffers,index,center.subtract(right).subtract(up),center.add(right).subtract(up),
                center.add(right).add(up),center.subtract(right).add(up),1,0,alpha);
    }

    private static void quad(PoseStack pose,MultiBufferSource buffers,int index,Vec3 a,Vec3 b,Vec3 c,Vec3 d,double bottom,double top,float alpha) {
        var out=buffers.getBuffer(SkillMesh.layer(ATLAS));
        float u=index%4*.25f,v=index/4*.25f;
        Vec3[] points={a,b,c,d};
        for(int i=0;i<4;i++) {
            Vec3 p=points[i];
            out.addVertex(pose.last(),(float)p.x,(float)p.y,(float)p.z)
                    .setColor(255,255,255,Math.round(255*Math.clamp(alpha,0,1)))
                    .setUv(u+(i==1||i==2?.25f:0),v+(float)(i<2?bottom:top)*.25f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xf000f0).setNormal(pose.last(),0,1,0);
        }
    }

    record Flame(Vec3 base,double width,double height,double phase,double delay,double front,double frontDistance,double side) {}
    static final class Field {
        final long start;
        final Vec3 origin;
        final List<Flame> flames=new ArrayList<>();
        Field(long start,Vec3 base,float heading,int seed) {
            this.start=start;this.origin=base;Random random=new Random(seed);
            Vec3 forward=Vec3.directionFromRotation(0,heading),right=new Vec3(forward.z,0,-forward.x);
            for(int i=0;i<80;i++) {
                double angle=i*2.399963, radius=.5+Math.sqrt(random.nextDouble())*3.5;
                Vec3 offset=new Vec3(Math.cos(angle)*radius,0,Math.sin(angle)*radius);
                Vec3 point=base.add(offset);
                var hit=MC.level.clip(new ClipContext(point.add(0,2,0),point.add(0,-4,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,MC.player));
                if(hit.getType()!=HitResult.Type.MISS)point=hit.getLocation().add(0,.018,0);
                double front=offset.normalize().dot(forward);
                flames.add(new Flame(point,1.25+random.nextDouble()*.65,1.8+random.nextDouble()*1.0,
                        random.nextDouble()*10,random.nextDouble()*.14,front,offset.dot(forward),offset.dot(right)));
            }
        }
    }

    static void hand(PoseStack pose,MultiBufferSource buffers,AbstractClientPlayer player,float partial,boolean yUp,boolean left) {
        StyleState state=AnimationRenderer.STATES.get(player.getUUID());
        if(!awakened(state))return;
        double time=state.phase().equals(StyleService.AWAKENING)?age(state,partial):10+(MC.level.getGameTime()+partial)%200/20;
        double alpha=ease((time-3.8)/.7);
        if(alpha<=0)return;
        pose.pushPose();
        if(!yUp)pose.scale(1,-1,1);
        double x=((left?1:-1)-SwordModel.slimHandOffset(player,left))/16.0;
        int frame=8+Math.floorMod((int)(time*10),4);
        // Wrap just outside the four skin faces, instead of hiding crossed sheets inside the fist.
        // The same arm-local coordinates work for the detached FP rig and the world model.
        double halfWidth=SwordModel.slimHandOffset(player,left)!=0?.102:.134;
        for(int face=0;face<4;face++) {
            double angle=face*Math.PI/2;
            Vec3 normal=new Vec3(Math.sin(angle),0,Math.cos(angle));
            Vec3 side=new Vec3(Math.cos(angle),0,-Math.sin(angle));
            double offset=face%2==0?.134:halfWidth;
            cell(pose,buffers,frame,new Vec3(x,-.56,0).add(normal.scale(offset)),
                    side.scale(face%2==0?halfWidth:.134),new Vec3(0,.18,0),(float)(.68*alpha));
        }
        for(int edge=0;edge<2;edge++) {
            Vec3 base=new Vec3(x+(edge==0?-halfWidth:halfWidth),-.71,(edge==0?-.09:.09));
            flame(pose,buffers,base,.15,.31,time,player.getId()*.17+edge*.7,2,(float)(.65*alpha));
        }
        pose.popPose();
    }

    private static final class Hands extends RenderLayer<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> {
        Hands(RenderLayerParent<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> parent){super(parent);}
        @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer p,float swing,float amount,float partial,float age,float yaw,float pitch) {
            if(p.isInvisible())return;
            for(int side=0;side<2;side++) {
                var arm=side==0?getParentModel().leftArm:getParentModel().rightArm;
                pose.pushPose();arm.translateAndRotate(pose);hand(pose,buffers,p,partial,false,side==0);pose.popPose();
            }
        }
    }

    static void guiCell(GuiGraphics g,int index,int x,int y,int width,int height,float alpha) {
        RenderSystem.enableBlend();
        g.setColor(1,1,1,Math.clamp(alpha,0,1));
        g.blit(ATLAS, x, y, width, height, index % 4 * ATLAS_CELL, index / 4 * ATLAS_CELL,
                ATLAS_CELL, ATLAS_CELL, 4 * ATLAS_CELL, 4 * ATLAS_CELL);
        g.setColor(1,1,1,1);
    }

    static void hotbar(GuiGraphics g,int x,int y,double tick) {
        RenderSystem.enableBlend();
        for(int side=0;side<2;side++)for(int i=0;i<5;i++) {
            int px=side==0?x-60+i*12:x+25+i*12;
            int cell=8+Math.floorMod((int)(tick*.5)+i+side,4);
            guiCell(g,cell,px,y-10,17,22,.87f);
        }
        for(int i=0;i<3;i++)guiCell(g,8+Math.floorMod((int)(tick*.5)+i,4),x+2,y-38+i*10,16,20,.85f);
    }

    static void title(GuiGraphics g,float partial) {
        StyleState state=StyleClient.local();
        if(state==null || !state.phase().equals(StyleService.AWAKENING))return;
        double time=age(state,partial),titleAge=time-4.5;
        if(time>=3 && time<3.18)g.fill(0,0,g.guiWidth(),g.guiHeight(),((int)(38*(1-(time-3)/.18))<<24)|0xa9e78b);
        if(titleAge<0 || titleAge>2)return;
        float alpha=(float)(ease(titleAge/.3)*(1-ease((titleAge-1.55)/.45)));
        double transform=ease((titleAge-.6)/.18);
        double kick=titleAge>=.6?Math.exp(-(titleAge-.6)*11):0;
        int size=StyleClient.titleSize(g),cx=g.guiWidth()/2,cy=StyleClient.titleTop(g)+size/2;
        g.pose().pushPose();
        g.pose().translate(cx+Math.sin(titleAge*100)*kick*3,cy+Math.sin(titleAge*125)*kick*2,0);
        float scale=(float)(1+.08*kick);g.pose().scale(scale,scale,1);
        RenderSystem.enableBlend();
        for(int layer=0;layer<2;layer++) {
            float a=alpha*(float)(layer==0?1-transform:transform);
            if(a<.016)continue;
            g.setColor(1,1,1,a);
            int sourceSize=layer==0?1254:256;
            g.blit(layer==0?SYMBOL:AWAKENED_SYMBOL,-size/2,-size/2,size,size,0,0,
                    sourceSize,sourceSize,sourceSize,sourceSize);
            g.setColor(1,1,1,1);
            String name=layer==0?"§lEstilo Três Espadas":"§lTrês Espadas: Demônio Desperto";
            int color=((int)(a*255)<<24)|(layer==0?0xffe8c5:0xc5ffdf);
            g.drawCenteredString(MC.font,name,0,size/2+4,color);
        }
        if(transform>0) {
            if(kick>.02) {
                for(int i=0;i<12;i++) {
                    double angle=i*Math.TAU/12;
                    double radius=size*(.45+.5*(1-kick));
                    guiCell(g,12+i%2,(int)(Math.cos(angle)*radius)-3,
                            (int)(Math.sin(angle)*radius)-3,6,6,alpha*(float)(kick*.7));
                }
            }
            for(int i=0;i<6;i++) {
                double a=i*Math.TAU/6+time*.18;
                int px=(int)(Math.cos(a)*size*.55)-5,py=(int)(Math.sin(a)*size*.48)-10;
                guiCell(g,(i%2)*4+Math.floorMod((int)(time*10)+i,4),px,py,10,20,alpha*(float)transform*.34f);
            }
        }
        g.pose().popPose();g.setColor(1,1,1,1);
    }
}
