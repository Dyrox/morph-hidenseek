package dev.morphhide.client;

import dev.morphhide.game.AnimalKind;
import dev.morphhide.game.MorphRegistry;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;

/** Detached animal models standing in for the local player's own body in GUI views. */
public final class DisguisePreview {
    private DisguisePreview() {}
    private static final Map<AnimalKind, Mob> MODELS = new EnumMap<>(AnimalKind.class);

    public static boolean localDisguised() {
        var player = Minecraft.getInstance().player;
        return player != null && MorphRegistry.CLIENT.containsKey(player.getUUID());
    }

    public static Mob forLocalPlayer() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        var kind = MorphRegistry.CLIENT.get(mc.player.getUUID());
        return kind == null ? null : model(kind);
    }

    public static Mob model(AnimalKind kind) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        var model = MODELS.get(kind);
        if (model == null || model.level() != mc.level) { model = PreviewEntities.create(kind.entityType, mc.level); MODELS.put(kind, model); }
        return model;
    }
}
