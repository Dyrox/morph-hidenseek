package dev.morphhide.test;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

/** Fake LAN guests on the integrated server: connect, tick, disconnect and reconnect with the same profile. */
final class TestBots {
    private TestBots() {}
    private static final Map<ServerPlayer, EmbeddedChannel> BOTS = new LinkedHashMap<>();
    private static final Set<MinecraftServer> HOOKED = Collections.newSetFromMap(new WeakHashMap<>());

    static ServerPlayer connect(MinecraftServer server, String name, UUID id) {
        if (HOOKED.add(server)) ServerTickEvents.END_SERVER_TICK.register(ticking -> {
            for (var entry : List.copyOf(BOTS.entrySet())) {
                var bot = entry.getKey();
                if (bot.level().getServer() != ticking || bot.hasDisconnected()) continue;
                Object packet;
                while ((packet = entry.getValue().readOutbound()) != null)
                    if (packet instanceof ClientboundPlayerPositionPacket teleport)
                        bot.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(
                            teleport.id(), bot.getX(), bot.getY(), bot.getZ(), bot.getYRot(), bot.getXRot()));
                bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                bot.doTick();
            }
        });
        var cookie = CommonListenerCookie.createInitial(new GameProfile(id, name), false);
        var bot = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, bot, cookie);
        BOTS.put(bot, channel);
        return bot;
    }

    /** Same path as a real client dropping: fires Fabric DISCONNECT and removes the player. */
    static void disconnect(ServerPlayer bot) {
        bot.connection.onDisconnect(new DisconnectionDetails(Component.literal("test disconnect")));
        var channel = BOTS.remove(bot);
        if (channel != null) channel.finishAndReleaseAll();
    }

    static void disconnectAll() { for (var bot : List.copyOf(BOTS.keySet())) disconnect(bot); }
}
