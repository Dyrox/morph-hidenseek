package dev.morphhide.client.mixin;

import dev.morphhide.game.MorphRegistry;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A disguised player is drawn only as its animal. The vanilla invisibility flag alone is not enough:
 * vanilla resets it whenever effects refresh, which briefly showed the human model and its name tag.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class HideDisguisedPlayerMixin {
    @Inject(method="shouldRender",at=@At("HEAD"),cancellable=true)
    private <E extends Entity> void morphhide$hideHuman(E entity,Frustum culler,double camX,double camY,double camZ,float partialTicks,CallbackInfoReturnable<Boolean> cir){
        if(entity instanceof Player player&&MorphRegistry.CLIENT.containsKey(player.getUUID()))cir.setReturnValue(false);
    }
}
