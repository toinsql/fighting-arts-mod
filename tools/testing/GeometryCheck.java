package fightingarts.client;
public final class GeometryCheck {
    public static void check(){
        var mc=net.minecraft.client.Minecraft.getInstance();
        var eye=mc.player.getEyePosition();
        var patches=MoonSurface.project(eye,eye.add(net.minecraft.world.phys.Vec3.directionFromRotation(0,mc.player.getYRot()).scale(4.24)));
        long faces=patches.stream().map(MoonSurface.Patch::normal).distinct().count();
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
