package dev.morphhide.mixin;

import dev.morphhide.game.GameManager;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The arena's plains biome would spawn horses and extra animals outside the round's herd, which hunters could not hit. */
@Mixin(NaturalSpawner.class)
public abstract class NoNaturalSpawnMixin {
    @Inject(method="spawnForChunk",at=@At("HEAD"),cancellable=true)
    private static void morphhide$noSpawnsInArena(ServerLevel level,LevelChunk chunk,NaturalSpawner.SpawnState state,List<MobCategory> categories,CallbackInfo ci){
        if(level.dimension()==GameManager.DIMENSION)ci.cancel();
    }
}
