package fightingarts.client;
public final class GeometryCheck {
    public static void checkGuardMouth() {
        var mc=net.minecraft.client.Minecraft.getInstance();
        var bones=java.util.Map.of("right_item2",new float[]{7,11,13},"head",new float[]{2,4,6},
                "body",new float[]{4,8,12},"root",new float[]{1,3,5});
        var neutral=new AnimationRenderer.Pose(bones,bones,bones);
        try {
            var method=AnimationRenderer.class.getDeclaredMethod("defensePose",net.minecraft.client.player.AbstractClientPlayer.class,
                    fightingarts.StyleState.class,AnimationRenderer.Pose.class,double.class,boolean.class);
            method.setAccessible(true);
            var pose=(AnimationRenderer.Pose)method.invoke(null,mc.player,StyleClient.local(),neutral,(double)mc.level.getGameTime(),false);
            if (pose==neutral || !pose.rotation().containsKey("right_arm")) throw new AssertionError("FA-DEFENSE FAIL missing guard pose");
            for (String bone:bones.keySet()) {
                if (!java.util.Arrays.equals(bones.get(bone),pose.position().get(bone))
                        || !java.util.Arrays.equals(bones.get(bone),pose.rotation().get(bone))
                        || !java.util.Arrays.equals(bones.get(bone),pose.scale().get(bone)))
                    throw new AssertionError("FA-DEFENSE FAIL changed mouth hierarchy " + bone);
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    public static void clearStormPreview(){AwakeningStorm.clear();}
    public static double[] focusedStormBolt(java.util.UUID actor,double offset) {
        var strike=AwakeningStorm.schedule(actor.getLeastSignificantBits()).subList(0,2).stream()
                .max(java.util.Comparator.comparingDouble(s->Math.hypot(s.x(),s.z()))).orElseThrow();
        return new double[]{strike.start()+offset,strike.x(),strike.z()};
    }
    private static void stormAssert(boolean ok,String name){if(!ok)throw new AssertionError("FA-STORM FAIL "+name);}
    public static void checkStorm() {
        var strikes=AwakeningStorm.schedule(17);
        stormAssert(strikes.equals(AwakeningStorm.schedule(17)),"same synchronized seed gives identical strikes");
        for(double t=0;t<=5;t+=.002) {
            final double age=t;
            long count=strikes.stream().filter(s->s.active(age)).count();
            if(t<1.5)stormAssert(count==0,"no strikes before section two");
            if(t>=1.5 && t<2.9)stormAssert(count<=1,"only one strike at a time in section two");
            if(t>=3 && t<3.5)stormAssert(count>=8,"simultaneous half-second eruption");
            if(t>=3.5)stormAssert(count<=2,"at most two strikes after eruption");
        }
        for(var strike:strikes) {
            stormAssert(Math.hypot(strike.x(),strike.z())>=3,"strikes avoid the caster");
            stormAssert(strike.start()<=4,"frequency ends at four seconds");
            stormAssert(strike.active(strike.start()) && strike.active(strike.start()+.499)
                    && !strike.active(strike.start()+.5),"entire bolt cuts off exactly at half a second");
        }
        var source=new net.minecraft.world.phys.Vec3(3,7,0);
        var target=new net.minecraft.world.phys.Vec3(4,0,1);
        var first=AwakeningStorm.boltPath(source,target,17,0);
        var later=AwakeningStorm.boltPath(source,target,17,.25);
        stormAssert(first[0].equals(source) && first[first.length-1].distanceTo(target)<1e-9,"full bolt reaches ground immediately");
        stormAssert(first[9].distanceTo(later[9])>.01,"lightning shape animates");
        for(var point:later)stormAssert(Double.isFinite(point.lengthSqr()),"finite lightning geometry");
        stormAssert(AwakeningStorm.cloudOpacity(1.46)==1 && AwakeningStorm.cloudOpacity(5.4)==0,"cloud formation and dissipation");
        stormAssert(ScorchDecals.remainingOpacity(19.99,5)==1 && ScorchDecals.remainingOpacity(20,5)==1
                && ScorchDecals.remainingOpacity(20.75,5)==0,"soot holds for fifteen seconds after fire, then dissipates");
        double low=10,high=-10,minRadius=10,maxRadius=0;
        for(int i=0;i<80;i++) {
            var point=AwakeningEffects.moteOffset(i,.7,17);
            low=Math.min(low,point.y);high=Math.max(high,point.y);
            minRadius=Math.min(minRadius,point.horizontalDistance());maxRadius=Math.max(maxRadius,point.horizontalDistance());
            stormAssert(AwakeningEffects.moteOffset(i,1.46,17).distanceTo(new net.minecraft.world.phys.Vec3(0,.98,0))<1e-9,"every mote absorbed by 1.46 seconds");
        }
        stormAssert(high>1.85 && low<.25 && maxRadius<1 && maxRadius-minRadius>.35,"close noncylindrical orbits cover head and feet");
        var surfaces=ScorchDecals.project(new net.minecraft.world.phys.Vec3(240.5,100,-270.5),2);
        stormAssert(surfaces.stream().anyMatch(f->Math.abs(f.y()-99.5)<1e-9)
                && surfaces.stream().anyMatch(f->Math.abs(f.y()-100)<1e-9),"soot follows slab/stair collision tops");
        stormAssert(surfaces.stream().noneMatch(f->f.block().getX()==242 && f.block().getZ()==-270),"no soot spanning the open hole");
        System.out.println("FA-STORM PASS synchronized strike schedule, whole animated bolts, safe radius, cloud fade, soot lifetime, terrain projection and close spherical orbits");
    }
    public static void checkFirstPersonAttacks(net.minecraft.client.multiplayer.ClientLevel level) {
        var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"StandingTest");
        var standing = new net.minecraft.client.player.RemotePlayer(level,profile);
        var crouched = new net.minecraft.client.player.RemotePlayer(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"CrouchedTest"));
        standing.setOnGround(true);
        crouched.setOnGround(true);
        crouched.setPose(net.minecraft.world.entity.Pose.CROUCHING);

        long now = level.getGameTime();
        for (int combo=1; combo<=3; combo++) {
            long start = now-60+combo;
            var standingState = new fightingarts.StyleState(standing.getUUID(),"ACTIVE",now-40,combo,start,java.util.List.of());
            var crouchedState = new fightingarts.StyleState(crouched.getUUID(),"ACTIVE",now-40,combo,start,java.util.List.of());
            AnimationRenderer.recordFirstPersonAttack(standing,null,standingState);
            AnimationRenderer.recordFirstPersonAttack(crouched,null,crouchedState);
            AnimationRenderer.resetHandBlend(standing);
            AnimationRenderer.resetHandBlend(crouched);
            try {
                AnimationRenderer.skillLanded(standing);
                AnimationRenderer.sampleFirstPersonPose(standing,standingState,.25f);
                AnimationRenderer.sampleFirstPersonPose(crouched,crouchedState,.25f);
                var normal = AnimationRenderer.sampleFirstPersonPose(standing,standingState,3.25f);
                var low = AnimationRenderer.sampleFirstPersonPose(crouched,crouchedState,3.25f);
                if ((combo!=1) != AnimationRenderer.firstPersonStriking(standing,true)
                        || (combo!=2) != AnimationRenderer.firstPersonStriking(standing,false))
                    throw new AssertionError("FA-CROUCH-ATTACK FAIL expected hands for combo " + combo);
                for (String bone : combo == 3 ? java.util.List.of("right_arm","left_arm")
                        : java.util.List.of(combo == 1 ? "right_arm" : "left_arm")) {
                    if (normal == null || low == null || !java.util.Arrays.equals(normal.transform(bone),low.transform(bone)))
                        throw new AssertionError("FA-CROUCH-ATTACK FAIL combo " + combo + " " + bone);
                }
            } finally {
                AnimationRenderer.clearFirstPersonStrikes(standing);
                AnimationRenderer.clearFirstPersonStrikes(crouched);
            }
        }
        var combo = new fightingarts.StyleState(standing.getUUID(),"ACTIVE",now-70,1,now-10,java.util.List.of());
        var falling = new fightingarts.StyleState(standing.getUUID(),"ACTIVE",now-70,0,now-10,java.util.List.of(),
                -1,now-10,false,fightingarts.StyleMode.SIMPLE);
        AnimationRenderer.recordFirstPersonAttack(standing,null,combo);
        AnimationRenderer.recordFirstPersonAttack(standing,combo,falling);
        AnimationRenderer.sampleFirstPersonPose(standing,falling,.25f);
        AnimationRenderer.sampleFirstPersonPose(standing,falling,80.25f);
        if (!AnimationRenderer.firstPersonStriking(standing,true) || !AnimationRenderer.firstPersonStriking(standing,false))
            throw new AssertionError("FA-CROUCH-ATTACK FAIL a confirmed falling hit starts with both arms and survives a frame stall");
        AnimationRenderer.clearFirstPersonStrikes(standing);

        AnimationRenderer.resetHandBlend(standing);
        var repeat = new fightingarts.StyleState(standing.getUUID(),"ACTIVE",now-70,1,now,java.util.List.of());
        AnimationRenderer.recordFirstPersonAttack(standing,null,combo);
        AnimationRenderer.recordFirstPersonAttack(standing,combo,repeat);
        var firstPose = AnimationRenderer.sampleFirstPersonPose(standing,repeat,.25f).transform("right_arm");
        var intermediatePose = AnimationRenderer.sampleFirstPersonPose(standing,repeat,3.25f).transform("right_arm");
        var restartedPose = AnimationRenderer.sampleFirstPersonPose(standing,repeat,6.25f).transform("right_arm");
        if (java.util.Arrays.equals(firstPose,intermediatePose) || !java.util.Arrays.equals(firstPose,restartedPose))
            throw new AssertionError("FA-CROUCH-ATTACK FAIL queued attacks show motion and restart the same arm at frame zero");
        AnimationRenderer.clearFirstPersonStrikes(standing);
        System.out.println("FA-CROUCH-ATTACK PASS delayed combos, landing, crouching, falling hit and same-arm restart");
    }

    public static boolean liveFirstPersonStrike(net.minecraft.client.player.AbstractClientPlayer player) {
        return AnimationRenderer.firstPersonStriking(player,false) || AnimationRenderer.firstPersonStriking(player,true);
    }

    public static void check(){
        var mc=net.minecraft.client.Minecraft.getInstance();
        checkFirstPersonAttacks(mc.level);
        var eye=mc.player.getEyePosition();
        var patches=MoonSurface.project(eye,eye.add(net.minecraft.world.phys.Vec3.directionFromRotation(0,mc.player.getYRot()).scale(4.24)));
        long faces=patches.stream().map(fightingarts.MoonSlashSurface.Patch::normal).distinct().count();
        if(faces<2)throw new AssertionError("FA-GEOMETRY FAIL corner has "+faces+" face orientations");
        double near=patches.stream().flatMap(p->p.vertices().stream()).mapToDouble(v->v.position().z).min().orElseThrow();
        double far=patches.stream().flatMap(p->p.vertices().stream()).mapToDouble(v->v.position().z).max().orElseThrow();
        if(far-near<.9)throw new AssertionError("FA-GEOMETRY FAIL missing depth projection");
        for(int blade=0;blade<3;blade++) {
            var early=ScreenSlashes.path(854,480,.1,blade);
            var middle=ScreenSlashes.path(854,480,.75,blade);
            var expired=ScreenSlashes.path(854,480,1.2,blade);
            if(early[32].center().distanceTo(middle[32].center())<1 || early[32].alpha()<=0 || middle[32].alpha()<=0 || expired[32].alpha()>0.00001f)
                throw new AssertionError("FA-SLASH FAIL animated geometry, simultaneous visibility or lifetime");
            for(var knot:middle)if(!Double.isFinite(knot.center().lengthSqr()) || !Double.isFinite(knot.side().lengthSqr()))
                throw new AssertionError("FA-SLASH FAIL nonfinite mesh");
        }
        System.out.println("FA-SLASH PASS all three animate simultaneously and fade after one second");
        System.out.println("FA-GEOMETRY PASS faces="+faces+" depth="+(far-near));
    }

}
