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
        System.out.println("FA-GEOMETRY PASS faces="+faces+" depth="+(far-near));
    }

}
