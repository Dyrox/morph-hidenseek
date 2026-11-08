package dev.morphhide.client.mixin;

import dev.morphhide.game.LobbyControlWall;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Wall hitboxes carry their action id as a custom name; never draw it as a floating name tag. */
@Mixin(EntityRenderer.class)
public abstract class WallNameTagMixin {
    @Inject(method="shouldShowName",at=@At("HEAD"),cancellable=true)
    private void morphhide$hideWallName(Entity entity,double distanceSq,CallbackInfoReturnable<Boolean> cir){
        if(entity.getCustomName()!=null&&entity.getCustomName().getString().startsWith(LobbyControlWall.PREFIX))cir.setReturnValue(false);
    }
}
