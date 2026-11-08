package dev.morphhide.mixin;

import dev.morphhide.game.GameManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The food fields are objectives: jumping on them must not trample the crops. */
@Mixin(FarmlandBlock.class)
public abstract class ArenaFarmlandMixin {
    @Inject(method="fallOn",at=@At("HEAD"),cancellable=true)
    private void morphhide$noTrample(Level level,BlockState state,BlockPos pos,Entity entity,double fallDistance,CallbackInfo ci){
        if(level.dimension().equals(GameManager.DIMENSION)){entity.causeFallDamage(fallDistance,1.0F,entity.damageSources().fall());ci.cancel();}
    }
}
