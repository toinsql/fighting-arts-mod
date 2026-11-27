package fightingarts;

import net.minecraft.world.phys.Vec3;

/** Shared flight clock for the authoritative hit and the following vertebrae. */
public final class DragonFlight {
    private DragonFlight() {}
    public static int duration(Vec3 start,Vec3 end){return 12+(int)Math.ceil(start.distanceTo(end)/1.35);}
    public static Vec3 point(Vec3 start,Vec3 end,double tick){
        Vec3 forward=end.subtract(start).multiply(1,0,1).normalize();
        Vec3 crest=start.add(forward.scale(2.7)).add(0,4.6,0);
        Vec3 point;
        if(tick<10){
            double t=Math.clamp(tick/10,0,1);
            point=start.lerp(crest,t).add(0,Math.sin(t*Math.PI)*.75,0);
        }else{
            double t=Math.clamp((tick-10)/(duration(start,end)-10),0,1);
            // Return to the aiming ray soon after clearing the caster.
            Vec3 aimHeight=crest.subtract(new Vec3(0,2.15,0));
            point=aimHeight.lerp(end,t).add(0,2.15*Math.pow(1-t,3.6),0);
        }
        double wave=Math.sin(Math.PI*Math.clamp(tick/duration(start,end),0,1));
        return point.add(forward.cross(new Vec3(0,1,0)).scale(.36*Math.sin(tick*.39)*wave));
    }
}
