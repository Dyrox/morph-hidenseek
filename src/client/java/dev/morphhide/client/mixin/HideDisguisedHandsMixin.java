package dev.morphhide.client.mixin;

import dev.morphhide.client.DisguisePreview;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Animals have no human arm in first person. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HideDisguisedHandsMixin {
    @Inject(method="submitHandsWithItems",at=@At("HEAD"),cancellable=true)
    private void morphhide$noHumanArm(CallbackInfo ci){if(DisguisePreview.localDisguised())ci.cancel();}
}
