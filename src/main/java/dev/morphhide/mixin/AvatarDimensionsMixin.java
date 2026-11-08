package dev.morphhide.mixin;

import dev.morphhide.game.MorphRegistry;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Avatar.class)
public abstract class AvatarDimensionsMixin {
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
    private void morphhide$dimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        var avatar = (Avatar)(Object)this;
        var kind = (avatar.level().isClientSide() ? MorphRegistry.CLIENT : MorphRegistry.SERVER).get(avatar.getUUID());
        if (kind != null) cir.setReturnValue(kind.entityType.getDimensions());
    }
}
