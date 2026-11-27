package fightingarts;

import com.mojang.authlib.GameProfile;
import fightingarts.client.AnimationRenderer;
import fightingarts.client.AwakeningEffects;
import fightingarts.client.StyleClient;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

/** Isolated copied-world integration test plus reproducible multi-angle visual captures. */
final class AwakeningCheck {
    static final Minecraft MC=Minecraft.getInstance();
    static final Vec3 ORIGIN=new Vec3(220.5,100,-290.5);
    static int stage,clock,warmup,orbit,boltPreview;
    static volatile boolean ready,gateReady;
    static Cow near,far,outside;
    static float nearAfter;
    static RemotePlayer standIn;
    static final UUID ACTOR=UUID.fromString("41a9cc32-237e-4f1d-a16c-fd40ea377f35");
    static final Set<String> CAPTURED=new HashSet<>();
    static String capture;
    static List<ItemStack> swords;
    static long deadline;

    static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e)->tick());
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event)->{
            if(capture==null)return;
            String name=capture;capture=null;event.getGuiGraphics().flush();
            if(name.equals("awaken-10-first-person-aura-hotbar")) {
                try(var pixels=Screenshot.takeScreenshot(MC.getMainRenderTarget())) {
                    int bright=0;
                    for(int x=pixels.getWidth()*40/100;x<pixels.getWidth()*61/100;x++)
                        for(int y=pixels.getHeight()*22/100;y<pixels.getHeight()*74/100;y++) {
                            int color=pixels.getPixelRGBA(x,y),r=color&255,g=color>>8&255,b=color>>16&255;
                            if(g>210 && r<190 && b<220)bright++;
                        }
                    require(bright<50,"body lightning does not streak through the first-person near plane: "+bright);
                    System.out.println("FA-AWAKEN FP PASS no body-aura streaks in clear center view");
                }
            }
            Screenshot.grab(new File("."),name+".png",MC.getMainRenderTarget(),message->System.out.println(message.getString()));
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event)->{
            if(!(event.getEntity() instanceof ServerPlayer p))return;
            StyleState state=StyleService.current(p);
            if(state==null || !state.phase().equals(StyleService.AWAKENING))return;
            long age=p.level().getGameTime()-state.phaseStart();
            if(age==85) {
                require(near.getHealth()<far.getHealth() && far.getHealth()<200,"radial damage falls with distance");
                require(outside.getHealth()==200,"no damage outside 20 blocks");
                nearAfter=near.getHealth();
                require(p.getPersistentData().getInt("fightingarts_awakening")==0,"bar consumed once");
                require(p.position().distanceTo(ORIGIN)<.01,"caster remains anchored");
                StyleService.attack(p,false);
                require(StyleService.current(p).combo()==0,"attacks locked during cinematic");
                System.out.println("FA-AWAKEN DAMAGE PASS near="+near.getHealth()+" far="+far.getHealth()+" outside="+outside.getHealth());
            }
            if(age==110)require(near.getHealth()==nearAfter,"wave damages each entity only once");
        });
    }

    static void tick() {
        if(MC.player==null || MC.getSingleplayerServer()==null)return;
        MC.options.pauseOnLostFocus=false;
        if(MC.screen!=null)MC.setScreen(null);
        if(stage==0) {stage=1;server(AwakeningCheck::prepare);return;}
        if(!ready)return;
        if(++clock>1350)throw new AssertionError("FA-AWAKEN FAIL timeout stage="+stage);
        StyleState s=StyleClient.local();
        if(stage==1 && ++warmup==25) {
            server(p->{
                bar(p,100);StyleService.toggle(p,StyleMode.AWAKENED);
                require(StyleService.current(p)==null,"cannot awaken from no style");
                StyleService.toggle(p,StyleMode.SIMPLE);
            });stage=2;
        }
        if(stage==2 && s!=null && s.phase().equals("ACTIVE") && s.mode()==StyleMode.SIMPLE) {
            server(p->{
                StyleService.toggle(p,StyleMode.AWAKENED);
                require(StyleService.current(p).mode()==StyleMode.SIMPLE,"cannot awaken from simple mode");
                require(p.getPersistentData().getInt("fightingarts_awakening")==100,"invalid activation preserves full bar");
                StyleService.toggle(p,StyleMode.SIMPLE);
            });stage=3;
        }
        if(stage==3 && s!=null && s.phase().equals("OFF")) {server(p->StyleService.toggle(p,StyleMode.COMMON));stage=4;}
        if(stage==4 && s!=null && s.phase().equals("ACTIVE") && s.mode()==StyleMode.COMMON) {
            server(p->{
                bar(p,99);StyleService.toggle(p,StyleMode.AWAKENED);
                require(StyleService.current(p).mode()==StyleMode.COMMON,"requires completely full bar");
                bar(p,100);p.setOnGround(false);StyleService.toggle(p,StyleMode.AWAKENED);
                require(StyleService.current(p).mode()==StyleMode.COMMON,"cannot awaken airborne");
                p.setOnGround(true);p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,new ItemStack(Items.SHIELD));
                StyleService.toggle(p,StyleMode.AWAKENED);
                require(StyleService.current(p).mode()==StyleMode.COMMON,"offhand restriction applies to awakening");
                p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,ItemStack.EMPTY);
                near=cow(p,3);far=cow(p,17);outside=cow(p,22);
                gateReady=true;
                System.out.println("FA-AWAKEN GATES PASS none simple incomplete bar airborne and offhand");
            });stage=5;
        }
        if(stage==5 && gateReady) {StyleNetwork.requestToggle(StyleMode.AWAKENED);stage=6;}
        if(stage==6 && s!=null && s.phase().equals(StyleService.AWAKENING)) {
            double age=(MC.level.getGameTime()-s.phaseStart())/20.0;
            if(age>.2 && age<1)shot("awaken-01-orbiting-motes");
            if(age>=1.2 && age<1.4)shot("awaken-02-absorption");
            if(age>=1.9 && age<2.3)shot("awaken-03-charge-fire");
            if(age>=2.05 && age<2.10)shot("storm-bolt-01-whole");
            if(age>=2.30 && age<2.35)shot("storm-bolt-02-animated");
            if(age>=2.50 && age<2.55)shot("storm-bolt-03-late");
            if(age>=2.55 && age<2.60)shot("storm-bolt-04-cutoff");
            if(age>=2.8 && age<3)shot("awaken-04-charge-close");
            if(age>=3.1 && age<3.22)shot("awaken-05-eruption");
            if(age>=3.8 && age<4.1) {
                shot("awaken-06-front-clearing");
                Vec3 sky=MC.level.getSkyColor(MC.player.position(),0);
                require(sky.y>sky.x*1.5 && sky.y>sky.z,"nearby sky turns green");
            }
            if(age>=4.65 && age<4.9)shot("awaken-07-normal-badge");
            if(age>=5.3 && age<5.65)shot("awaken-08-awakened-badge");
            if(age>=5.8 && age<6.2) {
                require(StyleClient.isCinematic(),"two-second camera tail stays active");
                shot("awaken-09-camera-tail");
            }
        }
        if(stage==6 && s!=null && s.mode()==StyleMode.AWAKENED && s.phase().equals("ACTIVE")) {
            require(MC.options.getCameraType()==CameraType.FIRST_PERSON,"restores original camera perspective");
            require(!StyleClient.isCinematic(),"releases cinematic camera and movement");
            mathematicalChecks();
            fightingarts.client.GeometryCheck.checkStorm();
            deadline=MC.level.getGameTime()+20;stage=7;
        }
        if(stage==7 && MC.level.getGameTime()>=deadline-4)shot("awaken-10-first-person-aura-hotbar");
        if(stage==7 && MC.level.getGameTime()>=deadline) {
            swords=s.swords();
            standIn=new RemotePlayer(MC.level,new GameProfile(ACTOR,"Awakened360"));
            standIn.setId(-4242);standIn.setPos(ORIGIN);standIn.setYRot(0);standIn.setYHeadRot(0);
            standIn.setYBodyRot(0);standIn.yBodyRotO=0;standIn.setOnGround(true);
            MC.level.addEntity(standIn);MC.options.setCameraType(CameraType.FIRST_PERSON);
            stage=8;orbit=-1;return;
        }
        if(stage==8) {
            if(orbit<0){orbit=0;return;}
            // The real renderer draws one fixed actor/pose from 8 positions per effect stage.
            int frame=orbit/9,view=frame%8,pass=frame/8;
            if(pass==6) {
                stage=83;boltPreview=0;
                double[] bolt=fightingarts.client.GeometryCheck.focusedStormBolt(ACTOR,0);
                Vec3 target=ORIGIN.add(bolt[1],3.5,bolt[2]);
                Vec3 eye=ORIGIN.add(bolt[1]+6,1.2,bolt[2]+8);
                Vec3 direction=target.subtract(eye.add(0,MC.player.getEyeHeight(),0));
                float yaw=(float)Math.toDegrees(Math.atan2(-direction.x,direction.z));
                float pitch=(float)-Math.toDegrees(Math.atan2(direction.y,direction.horizontalDistance()));
                server(p->p.connection.teleport(eye.x,eye.y,eye.z,yaw,pitch));return;
            }
            double time=new double[]{.7,2.1,3.12,3.95,5.5,21}[pass];
            fightingarts.client.GeometryCheck.clearStormPreview();
            AnimationRenderer.STATES.put(ACTOR,new StyleState(ACTOR,StyleService.AWAKENING,
                    MC.level.getGameTime()-Math.round(time*20),0,0,swords,-1,-1,false,StyleMode.AWAKENED));
            AnimationRenderer.STATES.put(MC.player.getUUID(),new StyleState(MC.player.getUUID(),"OFF",MC.level.getGameTime(),0,0,List.of()));
            if(orbit%9==0) {
                double angle=view*Math.TAU/8;
                Vec3 eye=ORIGIN.add(Math.sin(angle)*12,1.2,Math.cos(angle)*12);
                Vec3 direction=ORIGIN.add(0,1,0).subtract(eye.add(0,MC.player.getEyeHeight(),0));
                float yaw=(float)Math.toDegrees(Math.atan2(-direction.x,direction.z));
                float pitch=(float)-Math.toDegrees(Math.atan2(direction.y,direction.horizontalDistance()));
                server(p->{p.connection.teleport(eye.x,eye.y,eye.z,yaw,pitch);p.setNoGravity(true);});
            }
            if(orbit%9==6)shot("awaken-360-stage"+pass+"-angle"+(view*45));
            orbit++;
        }
        if(stage==83) {
            int frame=boltPreview/9;
            if(frame==4) {
                stage=81;deadline=MC.level.getGameTime()+12;
                server(p->p.connection.teleport(ORIGIN.x,101,ORIGIN.z+3.3,180,18));return;
            }
            double[] bolt=fightingarts.client.GeometryCheck.focusedStormBolt(ACTOR,new double[]{0,.2,.4,.5}[frame]);
            fightingarts.client.GeometryCheck.clearStormPreview();
            AnimationRenderer.STATES.put(ACTOR,new StyleState(ACTOR,StyleService.AWAKENING,
                    MC.level.getGameTime()-Math.round(bolt[0]*20),0,0,swords,-1,-1,false,StyleMode.AWAKENED));
            if(boltPreview%9==6)shot("storm-focused-"+frame);
            boltPreview++;
        }
        if(stage==81) {
            fightingarts.client.GeometryCheck.clearStormPreview();
            AnimationRenderer.STATES.put(ACTOR,new StyleState(ACTOR,StyleService.AWAKENING,
                    MC.level.getGameTime()-110,0,0,swords,-1,-1,false,StyleMode.AWAKENED));
            if(MC.level.getGameTime()>=deadline) {
                shot("awaken-11-close-hand-aura");stage=82;deadline=MC.level.getGameTime()+3;
            }
        }
        if(stage==82 && MC.level.getGameTime()>=deadline) {
            AnimationRenderer.STATES.remove(ACTOR);MC.level.removeEntity(-4242,net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            server(p->{StyleService.toggle(p,StyleMode.COMMON);require(StyleService.current(p).phase().equals("SHEATHING"),"X exits awakened mode");});
            stage=9;deadline=MC.level.getGameTime()+60;
        }
        if(stage==9 && MC.level.getGameTime()>=deadline) {
            server(p->{
                require(StyleService.current(p)==null,"exit cleans up awakened mode");
                p.setNoGravity(false);p.connection.teleport(ORIGIN.x,ORIGIN.y,ORIGIN.z,0,0);p.setOnGround(true);
                p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(2),"fightingarts debug awakening");
                require(p.getPersistentData().getInt("fightingarts_awakening")==100,"debug fills awakening bar");
                StyleService.toggle(p,StyleMode.COMMON);System.out.println("FA-AWAKEN EXIT PASS");
            });stage=10;
        }
        // Let the observer teleport/ground packets settle after the second common cinematic.
        if(stage==10 && s!=null && s.phase().equals("ACTIVE") && s.mode()==StyleMode.COMMON
                && MC.level.getGameTime()-s.phaseStart()>=5) {
            server(p->require(p.onGround(),"second activation is grounded: y="+p.getY()+" velocity="+p.getDeltaMovement()+" noGravity="+p.isNoGravity()));
            StyleNetwork.requestToggle(StyleMode.AWAKENED);stage=11;
        }
        if(stage==11 && s!=null && s.phase().equals(StyleService.AWAKENING) && MC.level.getGameTime()-s.phaseStart()>=15) {
            server(p->StyleWeapons.of(p).setItem(0,ItemStack.EMPTY));stage=12;
        }
        if(stage==12 && s!=null && s.phase().equals("OFF")) {
            require(!StyleClient.isCinematic() && MC.options.getCameraType()==CameraType.FIRST_PERSON,"missing sword cancels awakening and restores camera");
            System.out.println("FA-AWAKEN CANCEL PASS");
            System.out.println("FA-AWAKEN VISUAL PASS captured "+CAPTURED.size()+" views, including 48 views around fixed caster");
            System.out.println("FA-REVIEW PASS");stage=13;deadline=MC.level.getGameTime()+5;
        }
        if(stage==13 && MC.level.getGameTime()>=deadline)MC.stop();
    }

    static void mathematicalChecks() {
        Vec3 a=ORIGIN;
        double[] first=AwakeningEffects.camera(0,a,.2),second=AwakeningEffects.camera(0,a,1.1),burst=AwakeningEffects.camera(0,a,3.3);
        require(Arrays.equals(first,second),"camera remains fixed through initial charge");
        require(Math.abs(burst[2]-a.z)>13,"eruption pulls camera far back");
        require(AwakeningEffects.shakeAmplitude(3.05)>AwakeningEffects.shakeAmplitude(2.95)*3,"impact shake exceeds charge shake");
        require(AwakeningEffects.shakeAmplitude(4.5)==0,"shake fades to zero at clip end");
        require(AwakeningEffects.fireEnvelope(3.8,true)==0,"frontal fire clears by 3.75 seconds");
        require(AwakeningEffects.fireEnvelope(4.1,false)>0 && AwakeningEffects.fireEnvelope(5,false)==0,"remaining fire fades by five seconds");
        require(AwakeningEffects.influence(30)==0 && AwakeningEffects.influence(20)==1,"spectator effects respect radius");
        require(StyleService.awakeningDamage(0)==16 && StyleService.awakeningDamage(20)==2,"damage curve endpoints");
        System.out.println("FA-AWAKEN TIMELINE PASS camera shake fire and spectator boundaries");
    }
    static void shot(String name){if(CAPTURED.add(name))capture=name;}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError("FA-AWAKEN FAIL "+message);}
    static void server(Consumer<ServerPlayer> action){var server=MC.getSingleplayerServer();server.execute(()->action.accept(server.getPlayerList().getPlayers().getFirst()));}
    static void bar(ServerPlayer p,int amount) {
        try {
            var method=SantoryuSkills.class.getDeclaredMethod("combat",ServerPlayer.class);method.setAccessible(true);Object combat=method.invoke(null,p);
            var field=combat.getClass().getDeclaredField("awakeningHits");field.setAccessible(true);
            field.setInt(combat,Math.round(amount * (SantoryuSkills.AWAKENING_REQUIRED_HITS / 100f)));
            var sync=SantoryuSkills.class.getDeclaredMethod("sync",ServerPlayer.class,combat.getClass());sync.setAccessible(true);sync.invoke(null,p,combat);
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    static Cow cow(ServerPlayer p,double distance) {
        Cow c=EntityType.COW.create(p.level());c.setNoAi(true);c.setNoGravity(true);
        c.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);c.setHealth(200);
        c.setPos(ORIGIN.add(distance,0,0));p.level().addFreshEntity(c);return c;
    }
    static void prepare(ServerPlayer p) {
        var level=p.serverLevel();level.setDayTime(6000);level.setWeatherParameters(0,0,false,false);
        for(int x=195;x<=246;x++)for(int z=-316;z<=-265;z++) {
            level.setBlock(new BlockPos(x,99,z),Blocks.STONE.defaultBlockState(),3);
            for(int y=100;y<112;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        level.setBlock(new BlockPos(240,99,-270),Blocks.STONE_SLAB.defaultBlockState(),3);
        level.setBlock(new BlockPos(241,99,-270),Blocks.STONE_STAIRS.defaultBlockState(),3);
        for(int y=96;y<=99;y++)level.setBlock(new BlockPos(242,y,-270),Blocks.AIR.defaultBlockState(),3);
        p.connection.teleport(ORIGIN.x,ORIGIN.y,ORIGIN.z,0,0);p.setOnGround(true);
        p.getInventory().selected=8;p.getInventory().setItem(8,ItemStack.EMPTY);
        p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,ItemStack.EMPTY);
        var slots=StyleWeapons.of(p);
        slots.setItem(0,new ItemStack(Items.DIAMOND_SWORD));slots.setItem(1,new ItemStack(Items.GOLDEN_SWORD));slots.setItem(2,new ItemStack(Items.IRON_SWORD));
        MC.execute(()->MC.options.setCameraType(CameraType.FIRST_PERSON));
        ready=true;
    }
}
