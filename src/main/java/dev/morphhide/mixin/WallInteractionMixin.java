package dev.morphhide.mixin;

import dev.morphhide.game.LobbyControlWall;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Flat pick surfaces prevent a wide wall control from swallowing clicks metres in front of it. */
@Mixin(Interaction.class)
public abstract class WallInteractionMixin {
    private boolean morphhide$isWall(){
        var e=(Interaction)(Object)this;
        return e.getCustomName()!=null&&e.getCustomName().getString().startsWith(LobbyControlWall.PREFIX);
    }
    @Inject(method="makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;",at=@At("HEAD"),cancellable=true)
    private void morphhide$flatBox(Vec3 pos,CallbackInfoReturnable<AABB> cir){
        if(morphhide$isWall()){
            var e=(Interaction)(Object)this;double r=e.getWidth()/2;
            cir.setReturnValue(new AABB(pos.x-r,pos.y,pos.z-.04,pos.x+r,pos.y+e.getHeight(),pos.z+.04));
        }
    }
    @Inject(method="onSyncedDataUpdated",at=@At("TAIL"))
    private void morphhide$syncBox(EntityDataAccessor<?> data,CallbackInfo ci){
        if(morphhide$isWall())((Interaction)(Object)this).refreshDimensions();
    }
}
