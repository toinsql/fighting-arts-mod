package fightingarts;

import java.io.File;
import java.util.Map;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

final class VisualCheck {
    static int tick, warmup;
    static long upgradeStart = -1;
    static Cow extended, distant, covered, behind;
    static String capture;
    static final java.util.Set<String> captured = new java.util.HashSet<>();
    static volatile boolean ready;
    static final Minecraft MC = Minecraft.getInstance();

    static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> run());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event)->{
            if(!(event.getEntity() instanceof ServerPlayer p) || tick<43 || tick>110)return;
            if(p.getY()>100.1)seenAir=true;
            if(seenAir && p.onGround()) {
                long now=p.level().getGameTime();if(landedAt<0)landedAt=now;
                if(now-landedAt<10)require(SantoryuSkills.suppressFalling(p),"falling attack protected for half second after landing");
                if(now-landedAt>=11)require(!SantoryuSkills.suppressFalling(p),"falling attack protection expires");
            }
        });
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> {
            MC.options.pauseOnLostFocus=false; if(MC.player!=null && MC.screen!=null)MC.setScreen(null);
            if (MC.level != null && fightingarts.client.SkillHud.local()!=null && fightingarts.client.SkillHud.local().skill()==3) {
                try {
                    var impactField=fightingarts.client.SkillImpact.class.getDeclaredField("impactTick");impactField.setAccessible(true);
                    double impactAge=MC.level.getGameTime()+event.getPartialTick().getGameTimeDeltaPartialTick(false)-impactField.getDouble(null);
                    if(impactAge>=0 && impactAge<1.5)captureOnce("vendaval-triple-slash");
                    if(impactAge>=1.5 && impactAge<2.5)captureOnce("slash-animated-early");
                    if(impactAge>=6 && impactAge<7)captureOnce("slash-animated-middle");
                    if(impactAge>=15 && impactAge<16)captureOnce("slash-animated-late");
                    if(impactAge>=22 && impactAge<23)captureOnce("slash-animated-fade");
                }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            }
            if (capture == null) return;
            String name = capture;
            capture = null;
            event.getGuiGraphics().flush();
            Screenshot.grab(new File("."), name + ".png", MC.getMainRenderTarget(), message -> System.out.println(message.getString()));
        });
    }

    static void run() {
        if (MC.player == null || MC.getSingleplayerServer() == null) return;
        if(MC.screen instanceof net.minecraft.client.gui.screens.PauseScreen)MC.setScreen(null);
        if (tick == 0) {
            tick++;
            MC.options.pauseOnLostFocus = false;
            MC.options.setCameraType(CameraType.FIRST_PERSON);
            server(VisualCheck::prepare);
            return;
        }
        if (!ready || ++warmup < 40) return;
        if (warmup == 40) server(p -> StyleService.toggle(p, StyleMode.COMMON));
        var style = fightingarts.client.StyleClient.local();
        if (style == null) return;
        long age = MC.level.getGameTime() - style.phaseStart();
        if (style.phase().equals("DRAWING")) {
            if (age >= 1 && age < 7) captureOnce("hud-centered-common-down");
            if (age >= 12 && age < 30) {
                require(!style.entryAirborne(), "ground entry is not airborne");
                try {
                    var field = fightingarts.client.AnimationRenderer.class.getDeclaredField("ENTRY_LANDINGS");
                    field.setAccessible(true);
                    require(!((Map<?, ?>) field.get(null)).containsKey(MC.player), "no fall_ground on ground");
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                captureOnce("grounded-common-entry");
            }
        }
        if (tick == 1 && !style.phase().equals("ACTIVE")) return;
        tick++;
        if (tick >= 2 && tick < 9 && age >= 2 && age <= 5) captureOnce("hud-centered-common-up");
        if(tick==30)server(p->{reset(p);p.connection.teleport(210.5,100,-290.5,0,12);});
        if(tick==34) {aim(true);}
        if(tick==38)captureOnce("leap-preview");
        if(tick==40) {
            aim(false);
            var plan=LeapTrajectory.aim(MC.player,MC.player.position(),MC.player.getEyePosition(),MC.player.getLookAngle());
            require(plan!=null,"aim found ground");expected=plan.landing();
            System.out.println("FA-PLAN "+plan.velocity()+" to "+expected);
            SantoryuSkills.request(4,0);
        }
        if(tick==55)MC.player.setYRot(30);
        if(tick==65)require(Math.abs(MC.player.getYRot()-30)<.1,"camera remains free during leap");
        if(tick>42 && tick<110) {
            maxY=Math.max(maxY,MC.player.getY());
            if(tick==49 || tick==60 || tick==75)captureOnce("leap-"+tick);
        }
        if(tick==78)MC.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        if(tick==82){
            captureOnce("leap-landing");
            try {
                var field=fightingarts.client.AnimationRenderer.class.getDeclaredField("LANDINGS");field.setAccessible(true);
                require(((Map<?,?>)field.get(null)).containsKey(MC.player),"fall_ground registered on landing");
            }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        }
        if(tick==92)MC.options.setCameraType(CameraType.FIRST_PERSON);
        if(tick==110){
            System.out.println("FA-LEAP actual="+MC.player.position()+" expected="+expected+" apex="+maxY);
            require(MC.player.onGround(),"leap landed");
            require(Math.abs(maxY-110)<.7,"ten block apex");
            require(MC.player.position().distanceTo(expected)<1,"preview agrees with landing");
            require(fightingarts.client.StyleClient.local().fallingAttackStart()<0,"no automatic falling attack");
            server(p->{reset(p);p.connection.teleport(210.5,100,-290.5,0,12);});
        }
        if(tick==120)SantoryuSkills.request(4,0);
        if(tick==145){dashFrom=MC.player.position();SantoryuSkills.request(1,0);}
        if(tick==149){
            var status=fightingarts.client.SkillHud.local();
            require(status.skill()==1 && status.preparationTicks()==0,"air dash skips preparation");
            require(Math.abs(MC.player.position().subtract(dashFrom).horizontalDistance()-6)<.5,"air dash travels six blocks once");
            require(MC.player.getDeltaMovement().y<-.1,"descending dash never adds upward impulse");
            captureOnce("leap-dash");
        }
        if(tick==200)server(p->{reset(p);p.connection.teleport(220.5,100,-290.5,0,0);});
        if(tick==210)SantoryuSkills.request(2,0);
        if(tick==220) {captureOnce("moon-wall");server(p->require(SantoryuSkills.stunned(extended),"stun active after impact"));}
        if(tick==239)server(p->require(SantoryuSkills.stunned(extended),"stun remains after 1.2 seconds"));
        if(tick==250)server(p->require(!SantoryuSkills.stunned(extended),"stun expired after 1.5 seconds"));
        if(tick==260) {MC.player.setYRot(-55);captureOnce("moon-mob");}
        if(tick==280)server(p->{
            reset(p);p.connection.teleport(210.5,100,-290.5,0,12);
            testSurfaces(p);
        });
        if(tick==290)SantoryuSkills.request(4,0);
        if(tick==299)server(p->{
            airborne=cow(p,p.getX()+.2,p.getZ()+.5);airborne.setPos(p.position().add(0,.1,.5));
            airborne.setOnGround(false);
        });
        if(tick>=302 && tick<=310 && !airAttackSent && MC.level.getEntity(airborne.getId()) instanceof Cow target) {
            MC.hitResult=new net.minecraft.world.phys.EntityHitResult(target);
            clickAttack();airAttackSent=true;
        }
        if(tick==316){
            server(p->require(airborne.getHealth()<200,"real attack click deals damage during leap"));
            require(fightingarts.client.SkillHud.local().skill()==0,"air hit interrupts leap");
            require(fightingarts.client.StyleClient.local().fallingHitStart()>=0,"air hit plays hit_falling directly");
        }
        if(tick==370)server(p->{
            reset(p);p.connection.teleport(220.5,100,-290.5,-15,0);
            for(int y=100;y<104;y++)for(int x=219;x<=222;x++) {
                p.level().setBlockAndUpdate(new BlockPos(x,y,-288),Blocks.AIR.defaultBlockState());
                p.level().setBlockAndUpdate(new BlockPos(x,y,x<221?-288:-287),Blocks.STONE.defaultBlockState());
            }

        });
        if(tick==374)server(p->{for(int y=100;y<104;y++)for(int z=-289;z<=-286;z++)p.level().setBlockAndUpdate(new BlockPos(222,y,z),Blocks.STONE.defaultBlockState());});
        if(tick==378)fightingarts.client.GeometryCheck.check();
        if(tick==380)SantoryuSkills.request(2,0);
        if(tick==392){MC.player.setYRot(-12);captureOnce("moon-corner-depth");}
        if(tick==420)server(p->{reset(p);p.connection.teleport(210.5,105,-290.5,0,20);p.setOnGround(false);});
        if(tick==424)SantoryuSkills.request(4,0);
        if(tick==428){require(fightingarts.client.SkillHud.local().skill()==4,"leap starts airborne");captureOnce("airborne-leap");}
        if(tick==430){
            MC.hitResult=net.minecraft.world.phys.BlockHitResult.miss(MC.player.getEyePosition(),net.minecraft.core.Direction.UP,BlockPos.containing(MC.player.position()));
            clickAttack();
        }
        if(tick==434){
            require(fightingarts.client.SkillHud.local().skill()==0,"empty air swing interrupts leap");
            require(fightingarts.client.StyleClient.local().combo()>0,"empty air swing plays normal combo");
            require(fightingarts.client.StyleClient.local().fallingAttackStart()<0,"no falling preparation");
        }
        if(tick==460)server(p->{
            reset(p);p.connection.teleport(210.5,100,-290.5,0,14);
            for(int x=208;x<=212;x++)for(int z=-293;z<=-279;z++)p.level().setBlockAndUpdate(new BlockPos(x,103,z),Blocks.STONE.defaultBlockState());
        });
        if(tick==470)SantoryuSkills.request(4,0);
        if(tick>470 && tick<500)lowApex=Math.max(lowApex,MC.player.getY());
        if(tick==475)captureOnce("low-ceiling-leap");
        if(tick==500){
            require(lowApex>100.1 && lowApex<101.25,"actual low leap clears ceiling");
            require(MC.player.onGround(),"low leap lands");
            require(MC.player.getZ()>-286,"low leap reaches aimed floor");
            System.out.println("FA-LOW-LEAP PASS apex="+lowApex+" landed="+MC.player.position());
            server(p->{
                for(int x=208;x<=212;x++)for(int z=-293;z<=-279;z++)p.level().setBlockAndUpdate(new BlockPos(x,103,z),Blocks.AIR.defaultBlockState());
                reset(p);p.connection.teleport(210.5,100,-290.5,0,0);
            });
        }
        if(tick==520){dashFrom=MC.player.position();SantoryuSkills.request(1,0);}
        if(tick==525){
            require(fightingarts.client.SkillHud.local().preparationTicks()==15,"normal dash has 0.75 second preparation");
            require(MC.player.position().distanceTo(dashFrom)<.1,"normal dash waits for preparation");
        }
        if(tick==540)require(Math.abs(MC.player.position().subtract(dashFrom).horizontalDistance()-6)<.5,"normal dash travels six blocks");
        if(tick==575)server(p->{reset(p);p.connection.teleport(210.5,100,-290.5,0,0);});
        if(tick==580)SantoryuSkills.request(3,0);
        if(tick==605){
            var weak=fightingarts.client.SkillImpact.sample(14,-1);
            var strong=fightingarts.client.SkillImpact.sample(41,-1);
            var impact=fightingarts.client.SkillImpact.sample(43,0);
            require(Math.abs(strong.yaw())+Math.abs(strong.pitch())>Math.abs(weak.yaw())+Math.abs(weak.pitch()),"spin shake builds up");
            require(Math.abs(impact.yaw())+Math.abs(impact.pitch())>Math.abs(strong.yaw())+Math.abs(strong.pitch()),"impact exceeds charge");
            captureOnce("vendaval-charge-shake");
        }
        if(tick==625){
            try {
                var field=fightingarts.client.SkillImpact.class.getDeclaredField("impactTick");field.setAccessible(true);
                double impact=field.getDouble(null);
                require(Double.isFinite(impact) && MC.level.getGameTime()-impact<8,"dash packet triggers impact");
            }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            require(Math.abs(MC.player.getYRot())<.01 && Math.abs(MC.player.getXRot())<.01,"shake preserves aim");
            captureOnce("vendaval-impact-shake");
        }
        if(tick==650){
            var rest=fightingarts.client.SkillImpact.sample(70,20);
            require(rest.yaw()==0 && rest.pitch()==0 && rest.roll()==0 && rest.fov()==0,"camera fully recovers");
        }
        if(tick==710)server(p->{
            p.connection.teleport(210.5,100,-290.5,0,0);
            p.getInventory().setItem(8,new ItemStack(Items.STONE,16));p.getInventory().selected=8;
            p.level().setBlockAndUpdate(new BlockPos(210,101,-288),Blocks.DIRT.defaultBlockState());
            p.containerMenu.sendAllDataToRemote();
        });
        if(tick==720){
            MC.player.drop(false);MC.player.drop(true);
            MC.getConnection().send(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                MC.player.containerMenu.containerId,MC.player.containerMenu.getStateId(),8,0,
                net.minecraft.world.inventory.ClickType.THROW,ItemStack.EMPTY,new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>()));
            MC.gameMode.useItemOn(MC.player,net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(210.5,101.6,-288),net.minecraft.core.Direction.NORTH,new BlockPos(210,101,-288),false));
        }
        if(tick==725)server(p->{
            require(p.getInventory().getItem(8).getCount()==16,"drop packets preserve hidden hotbar stack");
            require(p.level().getBlockState(new BlockPos(210,101,-289)).isAir(),"hidden block stack cannot place");
        });
        if(tick==730){
            MC.hitResult=new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(210.5,101.6,-288),net.minecraft.core.Direction.NORTH,new BlockPos(210,101,-288),false);
            clickAttack();MC.options.keyAttack.setDown(true);MC.mouseHandler.grabMouse();
        }
        if(tick==734){
            require(!MC.gameMode.isDestroying(),"short block click does not mine");
            require(fightingarts.client.StyleClient.local().combo()>0,"short block click plays combat combo");
        }
        if(tick==740)require(!MC.gameMode.isDestroying(),"holding cannot start mining in attack mode");
        if(tick==770)server(VisualCheck::testSkillGuards);
        if(tick==790)server(p->{
            var before=p.getPersistentData().getInt("fightingarts_energy");
            use(p,2,0);
            require(p.getPersistentData().getInt("fightingarts_energy")==before,"cooldown rejection never consumes energy");
        });
        if(tick==830){
            MC.options.keyAttack.setDown(false);
            server(p->require(!p.level().getBlockState(new BlockPos(210,101,-288)).isAir(),"holding attack preserves block"));
        }
        if(tick==840)server(p->{
            require(!p.gameMode.destroyBlock(new BlockPos(210,101,-288)),"server blocks direct block destruction");
            StyleService.toggle(p,StyleMode.COMMON);
        });
        if(tick==1040)server(p->{
            require(!CombatRestrictions.active(p),"attack mode exited");
            require(p.gameMode.destroyBlock(new BlockPos(210,101,-288)),"normal block breaking restored outside attack mode");
        });
        if(tick==1060)server(p->{
            p.getInventory().setItem(8,ItemStack.EMPTY);
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,new ItemStack(Items.SHIELD));
            StyleService.toggle(p,StyleMode.COMMON);
            require(!CombatRestrictions.active(p),"offhand prevents mode entry");
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,ItemStack.EMPTY);
            StyleService.toggle(p,StyleMode.SIMPLE);
        });
        if(tick==1085)server(p->{
            require(StyleService.current(p).mode()==StyleMode.SIMPLE && StyleService.current(p).phase().equals("ACTIVE"),"simple entry completes");
            reset(p);use(p,1,0);
            require(p.getPersistentData().getInt("fightingarts_energy")==200,"simple mode cannot cast common skills");
            StyleService.toggle(p,StyleMode.COMMON);
        });
        if(tick==1120)require(fightingarts.client.StyleClient.local().phase().equals("UPGRADING"),"simple upgrade retains cinematic duration");
        if(tick==1185)require(fightingarts.client.StyleClient.local().phase().equals("ACTIVE"),"upgrade restores player control");
        if(tick==1200)server(p->{reset(p);use(p,3,0);});
        if(tick==1210)server(p->StyleWeapons.of(p).setItem(0,ItemStack.EMPTY));
        if(tick==1218){
            require(fightingarts.client.SkillHud.local().skill()==0,"losing a style sword cancels active skill");
            require(!fightingarts.client.SkillHud.movementLocked(),"cancellation releases movement");
            require(!fightingarts.client.StyleClient.attackMode(),"missing sword exits mode");
            captureOnce("cancelled-skill-restores-hud");
        }
        if(tick==1240)server(p->{
            p.getInventory().setItem(8,new ItemStack(Items.BOW));p.getInventory().selected=8;
            p.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
            require(p.isUsingItem(),"bow begins charging before stun");
            SantoryuSkills.clientStun(p);
            require(!p.isUsingItem(),"stun cancels existing bow charge");
            p.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
            require(!p.isUsingItem(),"stun blocks new bow charge");
            stunVictim=cow(p,p.getX(),p.getZ()+1);
            stunZombie=EntityType.ZOMBIE.create(p.level());stunZombie.setPos(p.position().add(1,0,0));p.level().addFreshEntity(stunZombie);
            SantoryuSkills.clientStun(stunZombie);
            require(!stunZombie.doHurtTarget(stunVictim),"stunned mob cannot melee");
            float health=stunVictim.getHealth();
            stunVictim.hurt(p.damageSources().mobAttack(stunZombie),10);
            require(stunVictim.getHealth()==health,"stunned attacker cannot deal damage");
            p.attack(stunVictim);
            require(stunVictim.getHealth()==health,"stunned player cannot deal damage");
            stunCreeper=EntityType.CREEPER.create(p.level());stunCreeper.setPos(p.position().add(4,0,0));p.level().addFreshEntity(stunCreeper);
            stunCreeper.ignite();
            try{var fuse=net.minecraft.world.entity.monster.Creeper.class.getDeclaredField("swell");fuse.setAccessible(true);fuse.setInt(stunCreeper,29);}
            catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            SantoryuSkills.clientStun(stunCreeper);
        });
        if(tick==1255)server(p->{
            require(stunCreeper.isAlive(),"primed creeper cannot explode during stun");
            try{var fuse=net.minecraft.world.entity.monster.Creeper.class.getDeclaredField("swell");fuse.setAccessible(true);require(fuse.getInt(stunCreeper)==0,"creeper fuse stays reset during stun");}
            catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            p.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
            require(!p.isUsingItem(),"bow cannot charge midway through stun");
        });
        if(tick==1275)server(p->{
            require(!SantoryuSkills.stunned(p),"stun expired normally");
            p.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
            require(p.isUsingItem(),"bow can charge after stun");p.stopUsingItem();
            try{var fuse=net.minecraft.world.entity.monster.Creeper.class.getDeclaredField("swell");fuse.setAccessible(true);int fuseAge=fuse.getInt(stunCreeper);require(fuseAge>0 && fuseAge<15,"creeper starts a fresh fuse after stun");}
            catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            stunVictim.invulnerableTime=0;float health=stunVictim.getHealth();
            stunVictim.hurt(p.damageSources().mobAttack(stunZombie),1);
            require(stunVictim.getHealth()<health,"mob damage restored after stun");
            stunCreeper.discard();stunZombie.discard();
            System.out.println("FA-STUN PASS player and mob damage, bow interruption, creeper fuse and recovery");
        });
        if(tick==1290){System.out.println("FA-REVIEW PASS");MC.stop();}
    }
    static net.minecraft.world.phys.Vec3 expected;
    static double maxY=100,lowApex=100;
    static Cow airborne,stunVictim;
    static net.minecraft.world.entity.monster.Zombie stunZombie;
    static net.minecraft.world.entity.monster.Creeper stunCreeper;
    static boolean airAttackSent;
    static boolean seenAir;
    static long landedAt=-1;
    static net.minecraft.world.phys.Vec3 dashFrom;
    static void aim(boolean active) {
        try {var field=fightingarts.client.SkillHud.class.getDeclaredField("aiming");field.setAccessible(true);field.setBoolean(null,active);}
        catch(Exception e){throw new IllegalStateException(e);}
        MC.options.keyHotbarSlots[3].setDown(active);
    }
    static void testSurfaces(ServerPlayer p) {
        var start=new net.minecraft.world.phys.Vec3(210.5,100,-290.5);
        var eye=start.add(0,p.getEyeHeight(),0);
        var sky=LeapTrajectory.aim(p,start,eye,new net.minecraft.world.phys.Vec3(0,.8,.6));
        require(sky!=null && Math.abs(sky.landing().subtract(start).horizontalDistance()-20)<.01,"sky aim uses horizontal limit");
        for(int x=209;x<=211;x++)for(int z=-284;z<=-280;z++)p.level().setBlockAndUpdate(new BlockPos(x,104,z),Blocks.STONE.defaultBlockState());
        var roof=LeapTrajectory.aim(p,start,eye,new net.minecraft.world.phys.Vec3(0,.34,1).normalize());
        require(roof!=null && roof.landing().y==105,"underside aim selects reachable roof surface");
        for(int x=209;x<=211;x++)for(int z=-284;z<=-280;z++)p.level().setBlockAndUpdate(new BlockPos(x,104,z),Blocks.AIR.defaultBlockState());
        for(int x=208;x<=212;x++)for(int z=-293;z<=-279;z++)p.level().setBlockAndUpdate(new BlockPos(x,103,z),Blocks.STONE.defaultBlockState());
        var low=LeapTrajectory.aim(p,start,eye,new net.minecraft.world.phys.Vec3(0,-.25,1).normalize());
        require(low!=null && low.landing().y==100,"ground under low ceiling remains target");
        require(low.path().stream().mapToDouble(point->point.y).max().orElseThrow()<101.25,"lower arc fits under ceiling");
        require(low.landing().z>-286,"low ceiling preserves requested distance");
        for(int x=208;x<=212;x++)for(int z=-293;z<=-279;z++)p.level().setBlockAndUpdate(new BlockPos(x,103,z),Blocks.AIR.defaultBlockState());
        System.out.println("FA-SURFACES PASS roof="+roof.landing()+" low="+low.landing());
    }
    static void clickAttack() {
        try {var method=Minecraft.class.getDeclaredMethod("startAttack");method.setAccessible(true);method.invoke(MC);}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    static void use(ServerPlayer p,int skill,float distance) {
        try {var method=SantoryuSkills.class.getDeclaredMethod("use",ServerPlayer.class,int.class,float.class);method.setAccessible(true);method.invoke(null,p,skill,distance);}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    static void testSkillGuards(ServerPlayer p) {
        try {
            reset(p);
            var method=SantoryuSkills.class.getDeclaredMethod("combat",ServerPlayer.class);method.setAccessible(true);
            Object combat=method.invoke(null,p);
            var energy=combat.getClass().getDeclaredField("energy");energy.setAccessible(true);
            energy.setInt(combat,0);use(p,1,0);
            require(p.getPersistentData().getLongArray("fightingarts_cooldowns")[0]==0,"insufficient energy never starts cooldown");
            reset(p);
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,new ItemStack(Items.SHIELD));
            use(p,1,0);
            require(p.getPersistentData().getInt("fightingarts_energy")==200,"offhand blocks skills without spending energy");
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,ItemStack.EMPTY);
            use(p,1,Float.NaN);use(p,1,Float.POSITIVE_INFINITY);
            require(p.getPersistentData().getLongArray("fightingarts_cooldowns")[0]==0,"nonfinite request rejected");
            use(p,2,0);
            int after=p.getPersistentData().getInt("fightingarts_energy");
            require(after==185,"skill 2 costs exactly 15 energy");
            use(p,2,0);
            require(p.getPersistentData().getInt("fightingarts_energy")==after,"busy skill cannot be charged twice");
            System.out.println("FA-GUARDS PASS energy cooldown offhand invalid packets and duplicate requests");
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    static void reset(ServerPlayer p){p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(2),"fightingarts debug reset");}

    static void captureOnce(String name) {
        if (captured.add(name)) capture = name;
    }

    static void server(java.util.function.Consumer<ServerPlayer> action) {
        var server = MC.getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayers().getFirst()));
    }

    static void prepare(ServerPlayer p) {
        var level = p.serverLevel();
        level.setDayTime(6000);
        for (var existing : level.getEntitiesOfClass(Cow.class,
                new net.minecraft.world.phys.AABB(200,90,-310,245,115,-260))) existing.discard();
        for (int x=204;x<=238;x++) for (int z=-300;z<=-261;z++) {
            level.setBlockAndUpdate(new BlockPos(x,99,z),Blocks.STONE.defaultBlockState());
            for (int y=100;y<=115;y++) level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        }
        for (int y=100;y<=102;y++) for (int x=219;x<=221;x++) {
            level.setBlockAndUpdate(new BlockPos(x,y,-288),Blocks.STONE.defaultBlockState());
        }
        extended = cow(p,223.7,-288.2);
        distant = cow(p,226.1,-288.2);
        covered = cow(p,220.5,-286.5);
        behind = cow(p,220.5,-292.5);
        p.connection.teleport(220.5,100,-290.5,0,0);
        var slots=StyleWeapons.of(p);
        slots.setItem(0,new ItemStack(Items.DIAMOND_SWORD));
        slots.setItem(1,new ItemStack(Items.GOLDEN_SWORD));
        slots.setItem(2,new ItemStack(Items.IRON_SWORD));
        p.getInventory().selected=8;
        p.getInventory().setItem(8, ItemStack.EMPTY);
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        p.setHealth(13);
        p.getFoodData().setFoodLevel(15);
        p.giveExperienceLevels(7);
        p.giveExperiencePoints(5);
        try {
            var field=SantoryuSkills.class.getDeclaredField("PLAYERS");
            field.setAccessible(true);
            ((Map<?,?>)field.get(null)).remove(p.getUUID());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        p.getPersistentData().putInt("fightingarts_energy",137);
        p.getPersistentData().putInt("fightingarts_awakening",52);
        p.getPersistentData().remove("fightingarts_awakening_hits");
        p.getPersistentData().putLongArray("fightingarts_cooldowns",new long[4]);

        ready=true;
    }

    static Cow cow(ServerPlayer p, double x, double z) {
        Cow cow=EntityType.COW.create(p.level());
        cow.setNoAi(true);
        cow.setNoGravity(true);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        cow.setHealth(200);
        cow.setPos(x,100,z);
        p.level().addFreshEntity(cow);
        return cow;
    }

    static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError("FA-VFX FAIL " + message);
    }
}

























