package dev.morphhide.mixin;

import dev.morphhide.game.AnimalKind;
import dev.morphhide.game.AnimalMovement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Like vanilla foxes, a fox-disguised player is neither slowed nor pricked by berry bushes. */
@Mixin(SweetBerryBushBlock.class)
public abstract class FoxBerryMixin {
    @Inject(method="entityInside",at=@At("HEAD"),cancellable=true)
    private void morphhide$foxImmune(BlockState state,Level level,BlockPos pos,Entity entity,InsideBlockEffectApplier effects,boolean precise,CallbackInfo ci){
        if(entity instanceof Player player&&AnimalMovement.form(player)==AnimalKind.FOX)ci.cancel();
    }
}
