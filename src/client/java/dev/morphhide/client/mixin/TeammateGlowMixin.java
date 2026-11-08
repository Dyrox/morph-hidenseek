package dev.morphhide.client.mixin;

import dev.morphhide.game.MorphRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Outline fellow animals on this client only; hunters never receive the ids. */
@Mixin(Minecraft.class)
public abstract class TeammateGlowMixin {
    @Inject(method="shouldEntityAppearGlowing",at=@At("HEAD"),cancellable=true)
    private void morphhide$glowTeammate(Entity entity,CallbackInfoReturnable<Boolean> cir){
        if(MorphRegistry.CLIENT_TEAMMATES.contains(entity.getId()))cir.setReturnValue(true);
    }
}
