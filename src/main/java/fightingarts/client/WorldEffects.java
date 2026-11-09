package fightingarts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fightingarts.StyleState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Camera-facing textured billboards positioned in world space around the fighter. */
final class WorldEffects {
    private static final ResourceLocation TRAIL = ResourceLocation.fromNamespaceAndPath("fightingarts", "textures/effect/trail.png");
    private static final ResourceLocation PARTICLE = ResourceLocation.fromNamespaceAndPath("fightingarts", "textures/effect/particle.png");
    private static final ResourceLocation AURA = ResourceLocation.fromNamespaceAndPath("fightingarts", "textures/gui/aura_back.png");
    private static final ResourceLocation SLASH = ResourceLocation.fromNamespaceAndPath("fightingarts", "textures/gui/slash_front.png");
    private static final double TRAIL_LIFETIME = .58;
    private static final float MOTION_EPSILON = .035f;
    private static final Deque<TrailSample>[] swordHistory = new Deque[]{new ArrayDeque<>(),new ArrayDeque<>(),new ArrayDeque<>(),new ArrayDeque<>()};
    private static UUID historyPlayer;

    private WorldEffects() {}

    static void stateChanged(StyleState next) {
        Minecraft mc = Minecraft.getInstance();
        if (next == null || mc.player == null || !next.playerId().equals(mc.player.getUUID())) return;
        if (next.phase().equals("OFF")) clearHistory();
    }

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        StyleState state = AnimationRenderer.STATES.get(mc.player.getUUID());
        if (state == null || !(state.phase().equals("DRAWING") || state.phase().equals("ACTIVE"))) return;

        PoseStack pose = event.getPoseStack();
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 playerPos = mc.player.getPosition(partial).add(0, 0.95, 0);
        if(!mc.player.getUUID().equals(historyPlayer)){clearHistory();historyPlayer=mc.player.getUUID();}
        double effectTime=(tickTime(mc,partial));
        pose.pushPose();
        pose.translate(playerPos.x - cameraPos.x, playerPos.y - cameraPos.y, playerPos.z - cameraPos.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        double impactAgeTicks = StyleClient.entryImpactAge(partial);
        if (impactAgeTicks >= 0 && impactAgeTicks < 1.7 * 20) {
            float impactAge = (float)(impactAgeTicks / 20.0);
            drawEntry(pose, buffers.getBuffer(RenderType.entityTranslucent(PARTICLE)), impactAge,camera.rotation());
            drawRotatingPlanes(pose,buffers,impactAge,camera.rotation());
        }
        if (state.phase().equals("ACTIVE") && state.combo() > 0) {
            drawTrails(pose, buffers.getBuffer(RenderType.entityTranslucent(TRAIL)), effectTime, mc.player.getYRot());
        }
        pose.popPose();
        buffers.endBatch();
    }

    private static void drawRotatingPlanes(PoseStack pose,MultiBufferSource.BufferSource buffers,float age,Quaternionf cameraRotation){
        float fade=(float)Math.min(1,Math.min(age*5,(1.7f-age)*2));
        float growth=.8f+Math.min(age,.7f)*.7f;
        VertexConsumer aura=buffers.getBuffer(RenderType.entityTranslucent(AURA));
        texturedBillboard(pose,aura,0,-.55f,0,growth,growth*2,fade,new Quaternionf().rotationX(-(float)Math.PI/2).rotateZ(age*2.8f));
        VertexConsumer slash=buffers.getBuffer(RenderType.entityTranslucent(SLASH));
        texturedBillboard(pose,slash,0,-.52f,0,growth*1.13f,growth*2.26f,fade,new Quaternionf().rotationX(-(float)Math.PI/2).rotateZ(-age*3.5f));
    }
    private static void drawEntry(PoseStack pose, VertexConsumer out, float age,Quaternionf cameraRotation) {
        float fade = Math.min(1f, (1.7f - age) * 1.8f);
        float radius = 0.3f + Math.min(age, 0.65f) * 0.85f;
        float spin = age * 5.4f;
        for (int i = 0; i < 24; i++) {
            float a = spin + i * (float)(Math.PI * 2 / 24);
            float y = -0.75f + ((i * 7) % 19) * 0.08f + age * (0.26f + (i % 3) * 0.06f);
            float r = radius + (i % 4) * 0.035f;
            float x = (float)Math.cos(a) * r, z = (float)Math.sin(a) * r;
            float size = 0.045f + (i % 3) * 0.014f;
            billboard(pose, out, x, y, z, size, size * 1.4f, (i % 2 == 0 ? 0xff67ff : 0x38ff9b), fade,cameraRotation);
        }
    }

    /** Receives the endpoints of the sword geometry after its final render transform. */
    static void recordSwordGeometry(net.minecraft.client.player.AbstractClientPlayer player,int sword,float partial,Vector3f baseWorld,Vector3f tipWorld){
        Minecraft mc=Minecraft.getInstance();if(mc.player==null||!player.getUUID().equals(mc.player.getUUID())||sword<0||sword>=swordHistory.length)return;
        StyleState state=AnimationRenderer.STATES.get(player.getUUID());
        if(!trailWindow(mc,state,partial))return;
        Vec3 playerPos=player.getPosition(partial);float yaw=(float)Math.toRadians(player.getYRot())-(float)Math.PI;
        Vector3f base=toPlayerAxes(new Vector3f(baseWorld).sub((float)playerPos.x,(float)playerPos.y,(float)playerPos.z),yaw);
        Vector3f tip=toPlayerAxes(new Vector3f(tipWorld).sub((float)playerPos.x,(float)playerPos.y,(float)playerPos.z),yaw);
        // Strip the body's translation and yaw before comparing. Idle bob/head sway and
        // global player movement therefore cannot create a false sword trail.
        if(base.distance(tip)<.05f)return;
        Deque<TrailSample> h=swordHistory[sword];double time=tickTime(mc,partial);TrailSample previous=h.peekLast();
        if (previous == null || previous.comboStart != state.comboStart()
                || Math.max(previous.base.distance(base), previous.tip.distance(tip)) >= MOTION_EPSILON) {
            h.addLast(new TrailSample(base, tip, time, state.comboStart()));
        }
        while(!h.isEmpty()&&time-h.peekFirst().time>TRAIL_LIFETIME)h.removeFirst();
    }

    private static void drawTrails(PoseStack pose,VertexConsumer out,double now,float yawDegrees){
        float yaw=(float)Math.PI-(float)Math.toRadians(yawDegrees);
        for (int sword = 0; sword < swordHistory.length; sword++) {
            Deque<TrailSample> history = swordHistory[sword];
            while (!history.isEmpty() && now - history.peekFirst().time > TRAIL_LIFETIME) history.removeFirst();
            TrailSample previous = null;
            for (TrailSample sample : history) {
                if (previous != null && previous.comboStart == sample.comboStart) {
                    float age = (float)Math.clamp((now - sample.time) / TRAIL_LIFETIME, 0, 1);
                    float alpha = .88f * (1 - age);
                    Vector3f b0 = toWorldAxes(previous.base, yaw), t0 = toWorldAxes(previous.tip, yaw);
                    Vector3f b1 = toWorldAxes(sample.base, yaw), t1 = toWorldAxes(sample.tip, yaw);
                    quadTrail(pose.last(), out, b0, t0, t1, b1, sword, alpha);
                }
                previous = sample;
            }
        }
    }

    private static Vector3f toPlayerAxes(Vector3f world,float inverseYaw){world.rotateY(inverseYaw);return world.mul(-1,-1,1);}
    private static Vector3f toWorldAxes(Vector3f local,float yaw){return new Vector3f(local).mul(-1,-1,1).rotateY(yaw).add(0,-.95f,0);}
    private static void quadTrail(PoseStack.Pose matrix,VertexConsumer out,Vector3f a,Vector3f b,Vector3f c,Vector3f d,int sword,float alpha){
        float r=sword==1?1f:1f,g=sword==2?.78f:.94f,bcol=1f;int packed=Math.max(0,Math.min(255,(int)(245*alpha)));
        trailVertex(matrix,out,a,r,g,bcol,packed,0,1);trailVertex(matrix,out,b,r,g,bcol,packed,0,0);trailVertex(matrix,out,c,r,g,bcol,packed,1,0);trailVertex(matrix,out,d,r,g,bcol,packed,1,1);
    }
    private static void trailVertex(PoseStack.Pose matrix,VertexConsumer out,Vector3f p,float r,float g,float b,int alpha,float u,float v){out.addVertex(matrix,p.x,p.y,p.z).setColor(r,g,b,alpha/255f).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(matrix,0,1,0);}
    private static double tickTime(Minecraft mc,float partial){return mc.level==null?0:(mc.level.getGameTime()+partial)/20.0;}
    private static boolean trailWindow(Minecraft mc,StyleState state,float partial){
        if (mc.level == null || state == null || !state.phase().equals("ACTIVE")
                || state.combo() < 1 || state.combo() > 3) return false;
        double age=(mc.level.getGameTime()-state.comboStart()+partial)/20.0;
        double start=state.combo()==3?.13:.08,end=state.combo()==3?.25:.21;
        return age >= start && age < end;
    }
    private record TrailSample(Vector3f base,Vector3f tip,double time,long comboStart){}
    private static void clearHistory(){for(Deque<TrailSample> h:swordHistory)h.clear();}

    private static void billboard(PoseStack pose, VertexConsumer out, float x, float y, float z,
                                  float width, float height, int rgb, float alpha,Quaternionf cameraRotation) {
        int a = Math.max(0, Math.min(255, (int)(alpha * 210)));
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        Quaternionf q = new Quaternionf(cameraRotation).normalize();
        Vector3f right = q.transform(new Vector3f(1, 0, 0)).mul(width);
        Vector3f up = q.transform(new Vector3f(0, 1, 0)).mul(height);
        float[][] p = {{x-right.x-up.x/2,y-right.y-up.y/2,z-right.z-up.z/2},
                {x+right.x-up.x/2,y+right.y-up.y/2,z+right.z-up.z/2},
                {x+right.x+up.x/2,y+right.y+up.y/2,z+right.z+up.z/2},
                {x-right.x+up.x/2,y-right.y+up.y/2,z-right.z+up.z/2}};
        float[][] uv={{0,1},{1,1},{1,0},{0,0}};
        var matrix=pose.last();
        for(int i=0;i<4;i++)out.addVertex(matrix,p[i][0],p[i][1],p[i][2]).setColor(r,g,b,a / 255f).setUv(uv[i][0],uv[i][1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(matrix,0,0,1);
    }
    private static void texturedBillboard(PoseStack pose,VertexConsumer out,float x,float y,float z,float width,float height,float alpha,Quaternionf cameraRotation){int a=Math.max(0,Math.min(255,(int)(alpha*210)));Quaternionf q=new Quaternionf(cameraRotation).normalize();Vector3f right=q.transform(new Vector3f(1,0,0)).mul(width);Vector3f up=q.transform(new Vector3f(0,1,0)).mul(height);float[][] p={{x-right.x-up.x/2,y-right.y-up.y/2,z-right.z-up.z/2},{x+right.x-up.x/2,y+right.y-up.y/2,z+right.z-up.z/2},{x+right.x+up.x/2,y+right.y+up.y/2,z+right.z+up.z/2},{x-right.x+up.x/2,y-right.y+up.y/2,z-right.z+up.z/2}};float[][] uv={{0,1},{1,1},{1,0},{0,0}};var matrix=pose.last();for(int i=0;i<4;i++)out.addVertex(matrix,p[i][0],p[i][1],p[i][2]).setColor(1,1,1,a/255f).setUv(uv[i][0],uv[i][1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(matrix,0,0,1);}
}


