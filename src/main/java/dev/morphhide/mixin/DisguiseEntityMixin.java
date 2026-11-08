package dev.morphhide.mixin;

import dev.morphhide.game.MorphRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionPath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clients drive disguises from their owner; ignore the server's (3-tick, interpolated) position updates. */
@Mixin(Entity.class)
public abstract class DisguiseEntityMixin {
    /** Sprint dust would reveal an animal's hidden player body. */
    @Inject(method="canSpawnSprintParticle",at=@At("HEAD"),cancellable=true)
    private void morphhide$noSprintDust(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir){
        if((Object)this instanceof net.minecraft.world.entity.player.Player player&&dev.morphhide.game.AnimalMovement.form(player)!=null)cir.setReturnValue(false);
    }
    @Inject(method="moveOrInterpolateTo(Lnet/minecraft/world/entity/PositionPath;FFZ)V",at=@At("HEAD"),cancellable=true)
    private void morphhide$ownerDriven(PositionPath position,float yRot,float xRot,boolean hasRotation,CallbackInfo ci){
        var self=(Entity)(Object)this;
        if(self.level().isClientSide()&&MorphRegistry.CLIENT_PROXIES.containsKey(self.getId()))ci.cancel();
    }
}
