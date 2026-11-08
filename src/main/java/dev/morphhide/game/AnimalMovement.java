package dev.morphhide.game;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Vanilla 26.3 walking inputs, translated to the player-controlled avatar. */
public final class AnimalMovement {
    private AnimalMovement() {}

    public static AnimalKind form(Player player) {
        return (player.level().isClientSide() ? MorphRegistry.CLIENT : MorphRegistry.SERVER).get(player.getUUID());
    }

    public static double walkingSpeed(AnimalKind kind) {
        // Rabbit.registerGoals uses 0.6 for strolling; the other five use 1.0.
        double stroll = kind == AnimalKind.RABBIT ? 0.6 : 1.0;
        return DefaultAttributes.getSupplier(kind.entityType).getValue(Attributes.MOVEMENT_SPEED) * stroll;
    }

    /** Vanilla TemptGoal speed modifiers (fox has none; it gets its follow-parent pace). */
    public static double temptModifier(AnimalKind kind) {
        return switch (kind) { case SHEEP -> 1.1; case COW -> 1.25; case PIG -> 1.2; case CHICKEN, RABBIT -> 1.0; case FOX -> 1.25; };
    }

    /** "Sprint" for an animal is the pace a real one uses to follow food, never a human sprint. */
    public static double fastSpeed(AnimalKind kind) {
        return DefaultAttributes.getSupplier(kind.entityType).getValue(Attributes.MOVEMENT_SPEED) * temptModifier(kind);
    }

    public static Vec3 input(Player player, Vec3 input) {
        AnimalKind kind = form(player);
        if (kind == null || player.isPassenger() || player.isSpectator()) return input;
        double walk = player.isSprinting() ? fastSpeed(kind) : walkingSpeed(kind);
        // MoveControl sets Mob.speed = attribute * goal multiplier, and Mob.setSpeed
        // also sets forward input to that same value. Ground acceleration is therefore
        // walk * walk, NOT walk. Keep the player's attribute intact (and its normal FOV)
        // and translate input instead, on both the predicting client and the server.
        boolean ground = player.onGround() && !player.isInWater() && !player.isInLava();
        double scale = ground ? (player.getSpeed() > 0 ? walk * walk / player.getSpeed() : 0) : walk;
        // LocalPlayer.modifyInput applies 0.98; AI MoveControl overwrites that damping.
        scale /= 0.98;
        double length = Math.sqrt(input.x * input.x + input.z * input.z);
        if (length > 1) scale /= length;
        return new Vec3(input.x * scale, input.y, input.z * scale);
    }
}
