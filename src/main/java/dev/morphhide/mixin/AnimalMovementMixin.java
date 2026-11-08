package dev.morphhide.mixin;

import dev.morphhide.game.AnimalMovement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Player.class)
public abstract class AnimalMovementMixin {
    @ModifyVariable(method = "travel", at = @At("HEAD"), argsOnly = true)
    private Vec3 morphhide$animalInput(Vec3 input) {
        return AnimalMovement.input((Player)(Object)this, input);
    }

}
