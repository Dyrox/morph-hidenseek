package dev.morphhide.mixin;

import dev.morphhide.game.AnimalMovement;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Animals may "sprint" (fast walk), but never get the human sprint-jump forward boost. */
@Mixin(LivingEntity.class)
public abstract class AnimalSprintMixin {
    @Redirect(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isSprinting()Z"))
    private boolean morphhide$noSprintJump(LivingEntity self) {
        return self.isSprinting() && !(self instanceof Player player && AnimalMovement.form(player) != null);
    }
}
