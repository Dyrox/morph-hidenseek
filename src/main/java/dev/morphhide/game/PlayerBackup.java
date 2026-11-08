package dev.morphhide.game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Durable pre-game snapshots also recover inventory and position after an interrupted server. */
public final class PlayerBackup {
    private final Path directory;
    public PlayerBackup(MinecraftServer server) {
        directory=server.getWorldPath(LevelResource.ROOT).resolve("morphhide-backups");
    }
    private Path path(ServerPlayer p) { return directory.resolve(p.getUUID()+".dat"); }
    public boolean exists(ServerPlayer p) { return Files.exists(path(p)); }
    public void save(ServerPlayer p) throws Exception {
        if(exists(p)) throw new IllegalStateException("Unrestored player backup: "+p.getUUID());
        Files.createDirectories(directory);
        var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,p.registryAccess());
        p.saveWithoutId(out);
        var tag=out.buildResult();
        tag.putString("mh_dimension",p.level().dimension().identifier().toString());
        tag.putDouble("mh_x",p.getX());tag.putDouble("mh_y",p.getY());tag.putDouble("mh_z",p.getZ());
        tag.putInt("mh_mode",p.gameMode.getGameModeForPlayer().getId());
        var temporary=directory.resolve(p.getUUID()+".tmp");
        NbtIo.writeCompressed(tag,temporary);
        Files.move(temporary,path(p),StandardCopyOption.ATOMIC_MOVE);
    }
    public void restore(ServerPlayer p) throws Exception {
        if(!exists(p)) return;
        var tag=NbtIo.readCompressed(path(p),NbtAccounter.unlimitedHeap());
        var target=p.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
            Identifier.parse(tag.getStringOr("mh_dimension","minecraft:overworld"))));
        if(target==null) target=p.level().getServer().overworld();
        MorphRegistry.SERVER.remove(p.getUUID());
        p.removeAllEffects();p.setInvisible(false);
        p.load(TagValueInput.create(ProblemReporter.DISCARDING,p.registryAccess(),tag));
        p.refreshDimensions();
        p.setGameMode(GameType.byId(tag.getIntOr("mh_mode",0)));
        p.teleportTo(target,tag.getDoubleOr("mh_x",0),tag.getDoubleOr("mh_y",80),tag.getDoubleOr("mh_z",0),
            Set.of(),p.getYRot(),p.getXRot(),true);
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
            net.minecraft.network.protocol.game.ClientboundGameEventPacket.CHANGE_GAME_MODE,p.gameMode.getGameModeForPlayer().getId()));
        p.level().getServer().getPlayerList().sendActivePlayerEffects(p);
        p.onUpdateAbilities();
        p.inventoryMenu.sendAllDataToRemote();
        p.level().getServer().getPlayerList().sendAllPlayerInfo(p);
        // Commit the restored player before deleting the recovery record.
        p.level().getServer().getPlayerList().saveAll();
        Files.delete(path(p));
    }
}
