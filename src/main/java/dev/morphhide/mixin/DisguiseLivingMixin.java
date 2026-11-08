package dev.morphhide.mixin;

import dev.morphhide.game.AnimalKind;
import dev.morphhide.game.DisguiseMotion;
import dev.morphhide.game.MorphRegistry;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class DisguiseLivingMixin {
    @Inject(method="lerpHeadTo",at=@At("HEAD"),cancellable=true)
    private void morphhide$ownerHead(float yRot,int steps,CallbackInfo ci){
        var self=(LivingEntity)(Object)this;
        if(self.level().isClientSide()&&MorphRegistry.CLIENT_PROXIES.containsKey(self.getId()))ci.cancel();
    }
    /** After the old position is stored and before animation/body rotation run, so walking legs animate. */
    @Inject(method="tick",at=@At("HEAD"))
    private void morphhide$followOwner(CallbackInfo ci){
        var self=(LivingEntity)(Object)this;
        if(!self.level().isClientSide()||!(self instanceof Mob mob))return;
        var owner=MorphRegistry.CLIENT_PROXIES.get(self.getId());if(owner==null)return;
        var player=self.level().getPlayerByUUID(owner);if(player==null)return;
        DisguiseMotion.follow(mob,player,MorphRegistry.CLIENT_EATING.contains(owner),MorphRegistry.CLIENT.get(owner)==AnimalKind.SHEEP);
    }
}
