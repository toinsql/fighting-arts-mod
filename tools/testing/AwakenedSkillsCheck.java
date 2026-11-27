package fightingarts;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

final class AwakenedSkillsCheck {
    private static int tick, bolts;
    private static final java.util.Set<String> captures = new java.util.HashSet<>();
    private static Cow wave, distantWave, behindWaveWall, echo, storm;
    private static boolean stepped;
    private static Cow repeatTarget,aimedSlash;
    private static final BlockPos AIMED_SLASH_WALL=new BlockPos(234,101,-292);
    private static final java.util.List<Long> rushHits=new java.util.ArrayList<>();
    private static Cow rush,dragon,secondDragon,lethal,yellow,insideWall,outsideWall,beyondSight;
    private static net.minecraft.world.entity.LivingEntity reinforcedPassive,reinforcedAttacker,glassCannon,twoHits,threeHits;
    private static double normalSpeed;
    private static long releaseSightAt, rushStepDeadline;
    private static volatile boolean clientSightChecked;
    private static float initialEcho, beforeBolts;
    private static final BlockPos WALL = new BlockPos(210,101,-288);
    private static final BlockPos BESIDE_CUT = new BlockPos(212,101,-288);

    static void install() {
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) -> {
            if(e.getEntity()==repeatTarget && e.getNewDamage()>0)rushHits.add(e.getEntity().level().getGameTime());
            if (e.getEntity()==storm && Math.abs(e.getNewDamage()-5)<.001) bolts++;
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) -> {
            var mc = Minecraft.getInstance();
            var state = fightingarts.client.SkillHud.local();
            if (mc.level == null || state == null) return;
            double age = mc.level.getGameTime()+e.getPartialTick().getGameTimeDeltaPartialTick(false)-state.start();
            String shot = null;
            if (state.skill()==1 && age>4 && age<12) shot="awakened-blade-charge";
            if (state.skill()==1 && age>16 && age<20) shot="awakened-traveling-wave";
            if (state.skill()==2 && age>10 && age<12) shot="awakened-wall-before-break";
            if (tick>=280 && tick<284) shot="awakened-inner-layer";
            if (state.skill()==3 && age>18 && age<36) shot="awakened-wind";
            if (state.skill()==3 && age>44) mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
            if (state.skill()==3 && age>71 && age<74) shot="awakened-cloud-bolt";
            if(tick>=416 && tick<=425) shot="rush";
            if(state.visionStart()>=0 && tick>=444 && tick<=476){
                double sightAge=mc.level.getGameTime()+e.getPartialTick().getGameTimeDeltaPartialTick(false)-state.visionStart();
                if(sightAge>=0 && sightAge<3)shot="sight-hands-start";
                if(sightAge>=6 && sightAge<13)shot="sight-hands-lowering";
                if(sightAge>=21 && sightAge<29)shot="sight-hands-hidden";
            }
            if(state.visionStart()<0 && tick>=497 && tick<=502)shot="sight-exit-mid";
            if(state.visionStart()<0 && tick>=516 && tick<=523)shot="sight-exit-done";
            if(tick>=480 && tick<=483) shot="blind-sight";
            if(tick>=484 && tick<=488) shot="sight-raw-damage";
            if(tick>=613 && tick<=619) shot="dragon-aim";
            if(tick>=628 && tick<=634) shot="dragon-lock";
            if(tick>=399 && tick<=400) shot="skill-icons";
            if(tick>=652 && tick<=654) shot="rift-dragon";
            if(tick>=666 && tick<=671) shot="rift-impact";
            if(tick>=1041 && tick<=1045 && state.visionStart()>=0)shot="sight-grass";
            if (shot!=null && captures.add(shot)) {
                e.getGuiGraphics().flush();
                net.minecraft.client.Screenshot.grab(new java.io.File("."),shot+".png",mc.getMainRenderTarget(),msg->{});
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post e) -> {
            if (e.getEntity() instanceof ServerPlayer p) run(p);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> {
            var mc = Minecraft.getInstance();
            mc.options.pauseOnLostFocus = false;
            for(var key:java.util.List.of(mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,
                    mc.options.keyJump,mc.options.keyShift,mc.options.keySprint))key.setDown(false);
            mc.options.renderDistance().set(6);
            mc.options.simulationDistance().set(5);
            if (mc.player != null && mc.screen != null) mc.setScreen(null);
        });
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError("FA-AWAKENED FAIL " + message);
    }

    private static boolean rendersInSight(Cow target){
        try {
            var field=Class.forName("fightingarts.client.BlindSight").getDeclaredField("visible");
            field.setAccessible(true);
            return ((java.util.List<?>)field.get(null)).stream()
                    .anyMatch(entity->((net.minecraft.world.entity.Entity)entity).getId()==target.getId());
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }

    private static boolean rawReadout(){
        try{
            var field=Class.forName("fightingarts.client.BlindSight").getDeclaredField("rawDamage");
            field.setAccessible(true);
            return field.getBoolean(null);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }

    private static int sightColor(net.minecraft.world.entity.LivingEntity target){
        var data=sightReport(target);
        return data==null?0:data.tint();
    }

    private static SantoryuSkills.SightTarget sightReport(net.minecraft.world.entity.LivingEntity target){
        try{
            var field=Class.forName("fightingarts.client.BlindSight").getDeclaredField("targets");
            field.setAccessible(true);
            return (SantoryuSkills.SightTarget)((java.util.Map<?,?>)field.get(null)).get(target.getId());
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static net.minecraft.world.entity.LivingEntity threatFixture(ServerPlayer p,double x,double z,
                                                                            float health,double armor,double attack) {
        var entity=net.minecraft.world.entity.EntityType.HUSK.create(p.serverLevel());
        entity.setNoAi(true);
        entity.setNoGravity(true);
        entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(health);
        entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR).setBaseValue(armor);
        entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).setBaseValue(attack);
        entity.setHealth(health);
        entity.setPos(x,101.3,z);
        p.serverLevel().addFreshEntity(entity);
        return entity;
    }

    private static Object combatField(ServerPlayer player,String name){
        try{
            var accessor=SantoryuSkills.class.getDeclaredMethod("combat",ServerPlayer.class);
            accessor.setAccessible(true);
            var state=accessor.invoke(null,player);
            var field=state.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(state);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }

    private static void verifyRemoteJump(){
        var mc=Minecraft.getInstance();
        var remote=new net.minecraft.client.player.RemotePlayer(mc.level,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"JumpObserverTest"));
        remote.setId(Integer.MAX_VALUE-7);
        remote.setPos(mc.player.getX()+2,mc.player.getY(),mc.player.getZ());
        mc.level.addEntity(remote);
        long now=mc.level.getGameTime();
        var states=fightingarts.client.AnimationRenderer.STATES;
        var swords=states.get(mc.player.getUUID()).swords();
        states.put(remote.getUUID(),new StyleState(remote.getUUID(),"ACTIVE",now,0,now,swords,-1,-1,false,StyleMode.AWAKENED));
        try{
            var renderer=(net.minecraft.client.renderer.entity.player.PlayerRenderer)mc.getEntityRenderDispatcher().getRenderer(remote);
            var field=fightingarts.client.AnimationRenderer.class.getDeclaredField("LEG_CYCLES");
            field.setAccessible(true);
            var cycles=(java.util.Map<?,?>)field.get(null);
            // Keep the remote position airborne between the two announcements, as if a bounce arrived in one update.
            remote.setOnGround(false);
            double[] poses=new double[2];
            for(int jump=0;jump<2;jump++){
                StyleNetwork.clientJumpReceiver.accept(new StyleNetwork.AirJump(remote.getUUID(),now,jump==0,.42f));
                fightingarts.client.AnimationRenderer.apply(renderer.getModel(),remote,.1f+jump*.3f);
                var legs=cycles.get(remote);
                var leading=legs.getClass().getDeclaredField("targetPhase");
                leading.setAccessible(true);
                double phase=leading.getDouble(legs);
                poses[jump]=phase-Math.floor(phase);
            }
            require(poses[0]==.5 && poses[1]==0,"remote jump announcements alternate the leading leg despite a missed touchdown");
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        finally{
            states.remove(remote.getUUID());
            mc.level.removeEntity(remote.getId(),net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
    }

    private static void run(ServerPlayer p) {
        int t = ++tick;
        var level = p.serverLevel();
        if (t == 40) {
            VisualCheck.prepare(p);
            var styleWeapons=StyleWeapons.of(p);
            var savedSwords=styleWeapons.swords();
            var oldItem=p.getInventory().getItem(14);
            for(int slot=0;slot<3;slot++)styleWeapons.setItem(slot,net.minecraft.world.item.ItemStack.EMPTY);
            p.getInventory().setItem(14,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_AXE));
            require(Math.abs(SantoryuSkills.playerThreat(p)-10)<.01,"raw inventory readout uses a weapon in a non-selected slot");
            p.getInventory().setItem(14,oldItem);
            for(int slot=0;slot<3;slot++)styleWeapons.setItem(slot,savedSwords.get(slot));
            require(Math.abs(SantoryuSkills.playerThreat(p)-17)<.01,"raw inventory readout considers the combined style weapons");
            StyleService.toggle(p, StyleMode.COMMON);
        }
        if (t < 65) return;
        if (t == 65 && (StyleService.current(p)==null || !StyleService.current(p).phase().equals("ACTIVE"))) { tick--; return; }
        if (t == 65) {
            VisualCheck.reset(p);
            for(int skill=5;skill<=7;skill++)VisualCheck.use(p,skill,0);
            require(p.getPersistentData().getInt("fightingarts_energy")==200,"common mode rejects awakened slots");
            p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(2),"fightingarts debug awakening");
            p.setOnGround(true);
            require(Math.abs(SantoryuSkills.playerThreat(p)-(17*1.35+7.5+34)/3)<.01,"common damage is the mean of three damaging skills");
            StyleService.toggle(p, StyleMode.AWAKENED);
            require(StyleService.current(p).mode()==StyleMode.AWAKENED,"activation accepted");
        }
        if (t == 205) {
            require(StyleService.current(p).mode()==StyleMode.AWAKENED && StyleService.current(p).phase().equals("ACTIVE"),"awakened active");
            require(Math.abs(SantoryuSkills.playerThreat(p)-(17*1.35*1.25+7.5*1.25+7.5+34+8.25+59.5)/5)<.01,
                    "awakened threat averages five damaging skills");
            VisualCheck.reset(p);
            p.connection.teleport(210.5,100,-290.5,0,0);
            var aim = LeapTrajectory.aim(p,p.position(),p.getEyePosition(),new Vec3(0,.4,1).normalize(),true);
            require(aim!=null && Math.abs(aim.landing().z-p.getZ()-25)<.01 && Math.abs(aim.path().stream().mapToDouble(v->v.y).max().orElseThrow()-120)<.1,"leap 25 x 20");
            wave=VisualCheck.cow(p,210.5,-273.5);
            distantWave=VisualCheck.cow(p,210.5,-264.5);
            behindWaveWall=VisualCheck.cow(p,210.5,-260.5);
        }
        if (t==210) VisualCheck.use(p,1,0);
        if (t==233) {
            // Build the wall after launch. The traveling slash must check current terrain.
            for (int y=100; y<=102; y++) for (int x=209; x<=211; x++)
                level.setBlockAndUpdate(new BlockPos(x,y,-262),Blocks.STONE.defaultBlockState());
        }
        if (t==240) {
            require(Math.abs(p.getZ()+279.5)<.1,"dash eleven blocks");
            require(Math.abs(wave.getHealth()-(200-17*1.35*1.25))<.02,"wave hits beyond dash once, damage="+(200-wave.getHealth()));
            require(Math.abs(distantWave.getHealth()-wave.getHealth())<.02,"wave hits at fifteen blocks from launch");
            require(behindWaveWall.getHealth()==200 && level.getBlockState(new BlockPos(210,101,-262)).is(Blocks.STONE),
                    "a wall placed during flight stops the slash");
            p.connection.teleport(210.5,100,-290.5,0,0);
            for(int i=0;i<3;i++) level.setBlockAndUpdate(WALL.south(i),Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(BESIDE_CUT,Blocks.STONE.defaultBlockState());
            Vec3 eye = p.getEyePosition();
            var patches = MoonSlashSurface.project(p,eye,eye.add(0,0,Math.sqrt(2)*3));
            require(patches.stream().anyMatch(patch->patch.block().equals(WALL)), "wall is marked");
            require(patches.stream().anyMatch(patch->patch.face()==net.minecraft.core.Direction.UP && patch.block().getY()==99),
                    "the longer slash reaches the ground with a level aim");
            require(patches.stream().noneMatch(patch->patch.block().equals(BESIDE_CUT)), "block beside the slash is unmarked");
            echo=VisualCheck.cow(p,212,-289);
        }
        if(t==254) VisualCheck.use(p,2,0);
        if(t==265) {
            initialEcho=echo.getHealth();
            require(Math.abs(initialEcho-(200-(4+7*.5)*1.25))<.01 && !level.isEmptyBlock(WALL),"first cut and delayed wall");
        }
        if(t==277) require(level.isEmptyBlock(WALL) && !level.isEmptyBlock(WALL.south()),
                "outer layer breaks before the inner layer breaks");
        if(t==279)require(!level.isEmptyBlock(WALL.south()),"second layer still solid after two ticks");
        if(t==280)require(level.isEmptyBlock(WALL.south()),"second layer breaks exactly three ticks later");
        if(t==288) {
            require(Math.abs(initialEcho-echo.getHealth()-7.5)<.01,"delayed left-hand hit");
            require(level.isEmptyBlock(WALL) && level.isEmptyBlock(WALL.south()) && !level.isEmptyBlock(WALL.south(2)),"exactly two layers");
            require(level.getBlockState(BESIDE_CUT).is(Blocks.STONE), "unmarked side block did not break");
            p.connection.teleport(215.5,100,-290.5,0,0);
            storm=VisualCheck.cow(p,218.5,-285.5);
            echo.discard();
        }
        if(t==290){
            // Capture the fixture heading and position together, after the wall case's outstanding client movement.
            p.connection.teleport(215.5,100,-290.5,0,0);
            VisualCheck.use(p,3,0);
        }
        if(t==330) require(Math.abs(storm.getX()-215.5)<1.5,"frontal wind pulled target at "+storm.position()+" caster "+p.position()+" yaw "+p.getYRot());
        if(t==337) beforeBolts=storm.getHealth();
        if(t==363) {
            float damage=beforeBolts-storm.getHealth();
            require(bolts==3 && beforeBolts<200 && damage>=15 && damage<=19.01,"three bolts and optional 4 fire damage, got "+damage);
            System.out.println("FA-AWAKENED PASS dash, wave, echo, terrain, wind, lightning, leap");
        }
        if(t==402){
            p.connection.teleport(227.5,100,-298.5,0,0);
            Minecraft.getInstance().execute(()->Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
            VisualCheck.reset(p);
            rush=VisualCheck.cow(p,228.55,-295.3);
            level.setBlockAndUpdate(new BlockPos(227,100,-292),Blocks.STONE.defaultBlockState());
        }
        if(t==409){
            var movement=net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED;
            double walkingSpeed=p.getAttributeValue(movement);
            rushStepDeadline=level.getGameTime()+45;
            VisualCheck.use(p,5,0);
            require(Math.abs(p.getAttributeValue(movement)-walkingSpeed*2)<.001,"rush doubles the movement attribute");
        }
        if(t>=411 && t<=432 && p.getZ()>-293 && p.getY()>=100.98)stepped=true;
        if(t==432){
            if(!stepped && level.getGameTime()<rushStepDeadline){tick--;return;}
            require(Math.abs(rush.getZ()+295.3-7)<.45,"seven horizontal blocks of knockback: "+rush.getZ());
            require(stepped,"auto-step climbs the full block: "+p.position());
            require(p.getZ()>-293 && rush.getHealth()==189.5f,"run advances and connects once with diamond sword: "+p.position()+" hp="+rush.getHealth());
            VisualCheck.use(p,-5,0);
            require(p.getPersistentData().getLongArray("fightingarts_cooldowns")[4]==level.getGameTime()+400,"release starts 20 s cooldown");
            require(p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT)<1,"step height restored");
        }
        if(t==444){
            p.server.setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
            p.removeAllEffects();
            p.setAbsorptionAmount(0);
            p.setHealth(13);
            p.getFoodData().setFoodLevel(15);
            for(var slot:java.util.List.of(net.minecraft.world.entity.EquipmentSlot.HEAD,
                    net.minecraft.world.entity.EquipmentSlot.LEGS,net.minecraft.world.entity.EquipmentSlot.FEET)){
                p.setItemSlot(slot,net.minecraft.world.item.ItemStack.EMPTY);
            }
            p.connection.teleport(210.5,100,-290.5,0,0);
            lethal=VisualCheck.cow(p,211.75,-286.6);lethal.setHealth(6);
            insideWall=VisualCheck.cow(p,210.0,-266.0);
            outsideWall=VisualCheck.cow(p,211.0,-265.0);
            beyondSight=VisualCheck.cow(p,218.5,-259.0);
            reinforcedPassive=threatFixture(p,206.5,-283.8,320,24,0);
            reinforcedAttacker=threatFixture(p,214.5,-283.8,320,24,1);
            glassCannon=threatFixture(p,212.7,-284.2,6,0,75);
            twoHits=threatFixture(p,213.2,-283.1,6,0,11);
            threeHits=threatFixture(p,214.2,-282.4,6,0,6);
            for(int x=208;x<=212;x++)for(int y=99;y<=104;y++)
                level.setBlockAndUpdate(new BlockPos(x,y,-285),Blocks.STONE.defaultBlockState());
            // Recess the pool so the water cannot move the player or the boundary targets.
            var sample=new BlockPos(207,99,-286);
            for(int x=207;x<=208;x++) for(int z=-288;z<=-285;z++){
                level.setBlockAndUpdate(new BlockPos(x,98,z),Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x,99,z),Blocks.WATER.defaultBlockState());
            }
            require(!SantoryuSkills.cuttableMaterial(level,sample,level.getBlockState(sample))
                    && !SantoryuSkills.cuttableMaterial(level,sample,Blocks.BEDROCK.defaultBlockState())
                    && SantoryuSkills.cuttableMaterial(level,sample,Blocks.STONE.defaultBlockState()),"water and bedrock uncuttable, stone cuttable");
            yellow=VisualCheck.cow(p,208.8,-286.9);yellow.setHealth(32);
            for(int i=0;i<11;i++)level.setBlockAndUpdate(WALL.south(i),Blocks.STONE.defaultBlockState());
            VisualCheck.reset(p);
            releaseSightAt=level.getGameTime()+220;
            normalSpeed=p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            VisualCheck.use(p,6,0);
        }
        if(t==468 || t==473){
            p.connection.teleport(210.5,100,-290.5,0,0);
            p.setDeltaMovement(Vec3.ZERO);
            p.hurtMarked=true;
        }
        if(t==479){
            float health=lethal.getHealth();
            int energy=p.getPersistentData().getInt("fightingarts_energy");
            long combo=StyleService.current(p).comboStart();
            StyleService.attack(p,false);
            p.attack(lethal);
            require(!lethal.hurt(p.damageSources().playerAttack(p),5),"active sight blocks outgoing attack damage");
            for(int skill:new int[]{1,2,3,5,7})VisualCheck.use(p,skill,0);
            require(StyleService.current(p).comboStart()==combo && lethal.getHealth()==health
                    && p.getPersistentData().getInt("fightingarts_energy")==energy,
                    "active sight blocks vanilla, combat packet and offensive skill attacks");
            require(Math.abs(p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)-normalSpeed*.5)<.001,"vision halves movement speed");
            require(!p.hasLineOfSight(insideWall) && !p.hasLineOfSight(outsideWall),
                    "both boundary fixtures hidden by wall: player="+p.position()+", inside="+insideWall.position()+", outside="+outsideWall.position());
            require(p.distanceTo(insideWall)<25 && p.distanceTo(outsideWall)>25,"fixtures straddle 25 block radius");
            require(SantoryuSkills.sightReveals(p,insideWall) && !SantoryuSkills.sightReveals(p,outsideWall) &&
                    !SantoryuSkills.sightReveals(p,beyondSight),"25 block wall and 30 block total range caps");
            Minecraft.getInstance().execute(()->{
                require(sightColor(insideWall)==SantoryuSkills.SIGHT_YELLOW && sightColor(outsideWall)==0 && sightColor(beyondSight)==0,
                        "client only receives forecast for the hidden mob inside 25 blocks: inner="+sightReport(insideWall)
                                +" outer="+sightReport(outsideWall)+" far="+sightReport(beyondSight));
                require(rendersInSight(insideWall) && !rendersInSight(outsideWall) && !rendersInSight(beyondSight),
                        "silhouette pass displays only the hidden mob inside 25 blocks");
                try{
                    var eyes=Class.forName("punchymoves.client.PlayerAnimationEngine")
                            .getMethod("emoteFace",net.minecraft.client.player.AbstractClientPlayer.class);
                    require("shut".equals(eyes.invoke(null,Minecraft.getInstance().player)),"Punchy closes eyes during sight");
                }catch(ClassNotFoundException optional){
                    // The renderer also supports running without Punchy Moves.
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}
                require(sightColor(reinforcedPassive)==SantoryuSkills.SIGHT_YELLOW,
                        "armor and high HP alone do not make a zero-damage mob red");
                require(sightColor(reinforcedAttacker)==SantoryuSkills.SIGHT_RED,
                        "one point of attack plus high HP and armor can make a mob red");
                require((int)Math.ceil(p.getHealth()/sightReport(glassCannon).damageAfterDefense())==1 && sightColor(glassCannon)==SantoryuSkills.SIGHT_RED,
                        "one lethal hit is red even for a fragile attacker");
                require((int)Math.ceil(p.getHealth()/sightReport(twoHits).damageAfterDefense())==2 && sightColor(twoHits)==SantoryuSkills.SIGHT_RED,
                        "two lethal hits are red: "+sightReport(twoHits)+" hp="+p.getHealth()+" armor="+p.getArmorValue());
                require((int)Math.ceil(p.getHealth()/sightReport(threeHits).damageAfterDefense())==3 && sightColor(threeHits)==SantoryuSkills.SIGHT_YELLOW,
                        "three lethal hits are yellow: "+sightReport(threeHits)+" hp="+p.getHealth()+" armor="+p.getArmorValue());
                var attack=sightReport(reinforcedAttacker);
                require(attack.rawDamage()==1 && attack.damageAfterDefense()>0 && attack.damageAfterDefense()<1,
                        "server sends raw and armor-mitigated max damage");
                require(!rawReadout(),"vision defaults to after-defense damage");
                var use=Minecraft.getInstance().options.keyUse;
                use.setDown(true);
                net.minecraft.client.KeyMapping.click(use.getKey());
                clientSightChecked=true;
            });
        }
        if(t==479)require(p.getPersistentData().getInt("fightingarts_energy")==182,"sight drains ten per second");
        if(t==488){
            require(!StyleDefense.guarding(p),"right click changes the damage readout instead of starting guard");
            Minecraft.getInstance().execute(()->{
                require(rawReadout(),"right click changes to raw damage and holding does not repeat the toggle");
                var use=Minecraft.getInstance().options.keyUse;
                use.setDown(false);
                net.minecraft.client.KeyMapping.click(use.getKey());
            });
            var reported=new int[]{sightColor(lethal),sightColor(yellow)};
            require(clientSightChecked && reported[0]==0xffffff && reported[1]==0xffd23e,"lethal and near-lethal forecasts");
        }
        if(t==492){
            if(level.getGameTime()<releaseSightAt){tick--;return;}
            require(!rawReadout(),"second right click restores after-defense damage");
            reinforcedPassive.discard();reinforcedAttacker.discard();glassCannon.discard();twoHits.discard();threeHits.discard();
            VisualCheck.use(p,6,0);
            require(Math.abs(p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)-normalSpeed)<.001,
                    "movement speed restored after vision");
        }
        if(t==493){
            require(p.getPersistentData().getLongArray("fightingarts_cooldowns")[5]==level.getGameTime()+599,"vision toggle cooldown");
        }
        if(t==520)VisualCheck.use(p,2,0);
        if(t==591){
            require(captures.contains("sight-exit-mid") && captures.contains("sight-exit-done"),
                    "first-person exit and restored HUD rendered");
            require(level.isEmptyBlock(WALL.south(9)) && !level.isEmptyBlock(WALL.south(10)),"vision cuts exactly ten layers");
            p.connection.teleport(232.5,100,-297.5,0,3);
            dragon=VisualCheck.cow(p,233.55,-276.5);
            secondDragon=VisualCheck.cow(p,232.5,-271.5);
            VisualCheck.reset(p);
        }
        if(t==595){
            p.connection.teleport(232.5,100,-297.5,48,-10);
            VisualCheck.use(p,7,0);
        }
        if(t==618)p.connection.teleport(232.5,100,-297.5,0,3);
        if(t==628)p.connection.teleport(232.5,100,-297.5,48,-10);
        if(t==637)p.connection.teleport(217.5,101,-285.5,-91,-4);
        if(t==683){
            require(Math.abs(secondDragon.getHealth()-(200-59.5f*.9f))<.02,"dragon pierces to the next mob with ten percent less damage: "+secondDragon.getHealth());
            require(dragon.getHealth()==140.5f,"dragon uses aim at 1.5 s, ignores turns after lock and hits the edge of its head sweep once: "+dragon.getHealth());
            require(p.getPersistentData().getLongArray("fightingarts_cooldowns")[6]==level.getGameTime()+1800-88,"dragon cooldown");
            System.out.println("FA-AWAKENED PASS rush, release, sight, ten layers, dragon damage and aim");
            VisualCheck.reset(p);
            for(int y=100;y<107;y++)level.setBlockAndUpdate(new BlockPos(206,y,-292),Blocks.STONE.defaultBlockState());
            p.connection.teleport(206.5,104,-296.5,0,0);
            p.setOnGround(false);
            VisualCheck.use(p,5,0);
            require(p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT)>=1,"airborne rush accepted");
        }
        if(t==888){
            require(p.getPersistentData().getLongArray("fightingarts_cooldowns")[4]>level.getGameTime(),"ten-second rush ends automatically");
            require(p.getPersistentData().getInt("fightingarts_energy")>=100 && p.getPersistentData().getInt("fightingarts_energy")<=101,
                    "full run costs 100 energy, got "+p.getPersistentData().getInt("fightingarts_energy"));
            VisualCheck.reset(p);
            p.connection.teleport(232.5,100,-297.5,0,3);
            for(int x=231;x<=233;x++)for(int y=100;y<=106;y++)
                level.setBlockAndUpdate(new BlockPos(x,y,-281),Blocks.STONE.defaultBlockState());
            VisualCheck.use(p,7,0);
        }
        if(t==973){
            require(dragon.getHealth()==140.5f && Math.abs(secondDragon.getHealth()-(200-59.5f*.9f))<.02,
                    "a wall intercepts the dragon before both targets");
            VisualCheck.reset(p);
            var client=Minecraft.getInstance();
            client.execute(()->{
                var key=client.options.keyHotbarSlots[4];
                key.setDown(true);net.minecraft.client.KeyMapping.click(key.getKey());
            });
        }
        if(t==988){
            require(SantoryuSkills.rushing(p),"held hotbar key reaches the server");
            var before=p.getDeltaMovement();
            p.jumpFromGround();
            require(p.getDeltaMovement().y==before.y,"rush suppresses jump impulse");
            var client=Minecraft.getInstance();
            client.execute(()->client.options.keyHotbarSlots[4].setDown(false));
        }
        if(t==998){
            require(!SantoryuSkills.rushing(p) && p.getPersistentData().getLongArray("fightingarts_cooldowns")[4]>level.getGameTime(),
                    "releasing the real hotbar binding ends the rush");
        }
        if(t==1018){
            VisualCheck.reset(p);
            level.getEntitiesOfClass(Cow.class,new net.minecraft.world.phys.AABB(214,98,-296,225,105,-283))
                    .forEach(Cow::discard);
            // Grass tops, a coplanar wall and a genuine height change in the same shot.
            for(int x=216;x<=224;x++)for(int z=-294;z<=-283;z++){
                level.setBlockAndUpdate(new BlockPos(x,99,z),Blocks.GRASS_BLOCK.defaultBlockState());
                if(z==-284 && x>=218 && x<=223)
                    for(int y=100;y<=102;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.GRASS_BLOCK.defaultBlockState());
                if(z==-288 && x>=222)
                    level.setBlockAndUpdate(new BlockPos(x,100,z),Blocks.GRASS_BLOCK.defaultBlockState());
            }
            level.setBlockAndUpdate(new BlockPos(220,100,-289),Blocks.SHORT_GRASS.defaultBlockState());
            var armed=threatFixture(p,222.2,-289.8,20,0,3);
            armed.setPos(222.2,100,-289.8);
            armed.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
            p.connection.teleport(220.5,100,-292.5,0,12);
            VisualCheck.use(p,6,0);
        }
        if(t==1046){
            require(p.getPersistentData().getInt("fightingarts_energy")<200,"vision active before expiration test");
            VisualCheck.use(p,6,0);
            require(p.getPersistentData().getLongArray("fightingarts_cooldowns")[5]==level.getGameTime()+600,
                    "expiration test starts at vision release");
        }
        if(t==1240)p.connection.teleport(210.5,100,-290.5,0,0);
        if(t==1247){
            for(int i=0;i<3;i++)level.setBlockAndUpdate(WALL.south(i),Blocks.STONE.defaultBlockState());
            VisualCheck.use(p,2,0);
        }
        if(t==1280){
            require(level.isEmptyBlock(WALL) && level.isEmptyBlock(WALL.south()) && !level.isEmptyBlock(WALL.south(2)),
                    "deep-cut entitlement expires 200 ticks after exit");
            require(captures.contains("sight-grass"),"grass contours captured");
        }
        if(t==1300){
            VisualCheck.reset(p);
            for(int x=224;x<=227;x++)for(int y=100;y<=103;y++)
                level.setBlockAndUpdate(new BlockPos(x,y,-294),Blocks.STONE.defaultBlockState());
            p.connection.teleport(225.5,100,-296.2,0,0);
            repeatTarget=VisualCheck.cow(p,226.4,-295.2);
            VisualCheck.use(p,5,0);
        }
        if(t==1315)require(rushHits.size()==1,"rush waits one second before a repeat hit: "+rushHits);
        if(t==1336){
            require(rushHits.size()==2 && rushHits.get(1)-rushHits.get(0)>=20,
                    "the same target can be hit again during one rush: "+rushHits);
            VisualCheck.use(p,-5,0);
        }
        if(t==1342){
            VisualCheck.reset(p);
            for(int x=205;x<=209;x++)for(int z=-299;z<=-295;z++)for(int y=100;y<=102;y++)
                level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.WATER.defaultBlockState());
            p.connection.teleport(207.5,100,-298.5,0,0);
            var start=new Vec3(207.5,245,-298.5);
            var launch=LeapTrajectory.aim(p,start,start.add(0,1.62,0),new Vec3(0,.95,.3).normalize(),true);
            require(launch.velocity().y>0 && launch.path().size()>1,"leap launches when no surface is reachable");
        }
        if(t==1350){
            require(p.isInWater(),"submerged leap fixture");
            p.setSwimming(true);
            p.updateSwimming();
            require(!p.isSwimming(),"awakened player cannot enter the swimming state");
            VisualCheck.use(p,4,0);
            require(SantoryuSkills.leapFlight(p) && !p.isAffectedByFluids() && p.getDeltaMovement().y>.5,
                    "submerged leap launches with air physics");
        }
        if(t==1352){
            VisualCheck.use(p,7,0);
            require(p.getPersistentData().getLongArray("fightingarts_cooldowns")[6]==level.getGameTime()+1500,
                    "leap dragon immediately starts the 75 second cooldown");
            require((long)combatField(p,"dragonTick")==level.getGameTime()
                    && Math.abs((float)combatField(p,"dragonDamage")-29.75f)<.01,"leap dragon launches immediately with half damage");
            require(SantoryuSkills.dragonDamageAfterHit(24f,23.8f,200)==23.8f
                    && Math.abs(SantoryuSkills.dragonDamageAfterHit(40,0,2)-39.6f)<.01,"dragon attenuation floor and minimum decay");
        }
        if(t==1378)Minecraft.getInstance().execute(AwakenedSkillsCheck::verifyRemoteJump);
        if(t==1400){
            VisualCheck.reset(p);
            p.connection.teleport(234.5,100,-295.5,0,15);
            level.setBlockAndUpdate(AIMED_SLASH_WALL,Blocks.STONE.defaultBlockState());
            aimedSlash=VisualCheck.cow(p,234.5,-293.3);
        }
        if(t==1405){
            var eye=p.getEyePosition();
            require(!MoonSlashSurface.project(p,eye,eye.add(0,-.7,4.24)).isEmpty(),"aimed slash fixture includes solid terrain");
            VisualCheck.use(p,2,0);
        }
        if(t==1440){
            require(Math.abs(aimedSlash.getHealth()-(200-7.5*1.25-7.5))<.02,
                    "aimed slash and its echo still damage the mob");
            require(level.getBlockState(AIMED_SLASH_WALL).is(Blocks.STONE)
                    && level.getBlockState(new BlockPos(234,99,-293)).is(Blocks.STONE),
                    "aimed slash leaves nearby wall and floor intact");
            StyleService.toggle(p,StyleMode.SIMPLE); // Sheath the awakened style.
        }
        if(t==1494){
            p.connection.teleport(207.5,100,-298.5,0,0);
            StyleService.toggle(p,StyleMode.SIMPLE);
        }
        if(t==1518){
            require(p.isInWater() && StyleService.current(p).mode()==StyleMode.SIMPLE,"simple style in water");
            p.setSwimming(true);
            p.updateSwimming();
            require(!p.isSwimming() && p.getSwimAmount(1)==0,"simple style prevents swim mechanics and pose");
            StyleService.toggle(p,StyleMode.COMMON);
        }
        if(t==1522){
            require(p.isInWater() && StyleService.current(p).mode()==StyleMode.COMMON,"common style in water");
            p.setSwimming(true);
            p.updateSwimming();
            require(!p.isSwimming() && p.getSwimAmount(1)==0,"common style prevents swim mechanics and pose");
        }
        if(t==1625){
            require(StyleService.current(p).phase().equals("ACTIVE"),"common style ready for the lava test");
            VisualCheck.reset(p);
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE,120));
            for(int x=234;x<=238;x++)for(int z=-300;z<=-296;z++)for(int y=100;y<=101;y++){
                var block=x==234 || x==238 || z==-300 || z==-296?Blocks.STONE:Blocks.LAVA;
                level.setBlockAndUpdate(new BlockPos(x,y,z),block.defaultBlockState());
            }
            p.connection.teleport(236.5,100,-298.5,0,0);
        }
        if(t==1632){
            require(p.isInLava(),"lava leap fixture");
            VisualCheck.use(p,4,0);
            require(SantoryuSkills.leapFlight(p) && !p.isAffectedByFluids() && p.getDeltaMovement().y>.5,
                    "lava leap launches with air physics");
        }
        if(t==1640){
            require(p.getY()>102.5 && !p.isInLava(),"leap lifts the player clear of the lava");
            System.out.println("FA-REVIEW PASS");
            Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
        }
    }
}
