package dev.morphhide.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Mannequin.class)
public interface MannequinAccessor {
    @Invoker("setDescription") void morphhide$setDescription(Component description);
    @Invoker("setHideDescription") void morphhide$setHideDescription(boolean hide);
    @Invoker("setImmovable") void morphhide$setImmovable(boolean immovable);
}
