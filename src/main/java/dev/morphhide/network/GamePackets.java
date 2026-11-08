package dev.morphhide.network;

import dev.morphhide.MorphHide;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class GamePackets {
    public record Action(String action) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MorphHide.ID, "action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUtf(value.action, 64), buf -> new Action(buf.readUtf(64)));
        @Override public Type<Action> type() { return TYPE; }
    }
    public record State(String json) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MorphHide.ID, "state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUtf(value.json, 32767), buf -> new State(buf.readUtf(32767)));
        @Override public Type<State> type() { return TYPE; }
    }
    public record OpenMenu(String page) implements CustomPacketPayload {
        public static final Type<OpenMenu> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MorphHide.ID, "open_menu"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenMenu> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUtf(value.page, 16), buf -> new OpenMenu(buf.readUtf(16)));
        @Override public Type<OpenMenu> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(State.TYPE, State.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenMenu.TYPE, OpenMenu.CODEC);
    }
}
