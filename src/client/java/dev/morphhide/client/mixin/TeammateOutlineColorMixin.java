package dev.morphhide.client.mixin;

import dev.morphhide.client.TeamHud;
import dev.morphhide.game.MorphRegistry;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class TeammateOutlineColorMixin {
    @Inject(method="getTeamColor",at=@At("HEAD"),cancellable=true)
    private void morphhide$teammateColor(CallbackInfoReturnable<Integer> cir){
        if(MorphRegistry.CLIENT_TEAMMATES.contains(((Entity)(Object)this).getId()))cir.setReturnValue(TeamHud.MATE_COLOR);
    }
}
