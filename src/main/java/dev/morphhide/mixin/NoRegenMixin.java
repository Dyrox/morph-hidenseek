package dev.morphhide.mixin;

import dev.morphhide.MorphHide;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real animals never regenerate; the lobby save is Peaceful, which would heal players 1 HP per second. */
@Mixin(LivingEntity.class)
public abstract class NoRegenMixin {
    @Inject(method="heal",at=@At("HEAD"),cancellable=true)
    private void morphhide$noAnimalRegen(float amount,CallbackInfo ci){
        if((Object)this instanceof ServerPlayer player&&MorphHide.game!=null&&MorphHide.game.isAnimalInRound(player))ci.cancel();
    }
}
