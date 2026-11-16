package net.minecraftforge.network.simple;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * Forge's simple channel as mods used it from 1.13 to 1.20.1, which Forge has
 * since replaced with one of another shape.
 *
 * <p>Within one game, which is what a single-player world is, a message is
 * handed straight to its handler on the other side's thread: the server's
 * messages to the client's, the client's to the server's, as the player who
 * is playing. Nothing is written to the connection, so <b>a player on another
 * computer receives none of these messages</b>: on a dedicated server, or a
 * world opened to LAN, a mod using this channel is not kept in step for them.
 */
public class SimpleChannel {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Octo/SimpleChannel");

    private final Object name;
    private final Map<Class<?>, IndexedMessageCodec.MessageHandler<?>> handlers = new ConcurrentHashMap<>();
    private boolean warned;

    public SimpleChannel(Object name) {
        this.name = name;
    }

    public <MSG> IndexedMessageCodec.MessageHandler<MSG> registerMessage(int index, Class<MSG> type,
            BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder,
            BiConsumer<MSG, Supplier<NetworkEvent.Context>> consumer) {
        return registerMessage(index, type, encoder, decoder, consumer, Optional.empty());
    }

    public <MSG> IndexedMessageCodec.MessageHandler<MSG> registerMessage(int index, Class<MSG> type,
            BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder,
            BiConsumer<MSG, Supplier<NetworkEvent.Context>> consumer, Optional<NetworkDirection> direction) {
        IndexedMessageCodec.MessageHandler<MSG> handler = new IndexedMessageCodec.MessageHandler<>(index, type,
                consumer);
        handlers.put(type, handler);
        return handler;
    }

    /** From the server to players: handed to the client of this same game. */
    public <MSG> void send(PacketDistributor.PacketTarget target, MSG message) {
        BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler = handlerOf(message);

        if (handler == null) {
            return;
        }

        try {
            Client.run(() -> deliver(handler, message, new NetworkEvent.Context(NetworkDirection.PLAY_TO_CLIENT, null)));
        } catch (LinkageError e) {
            warnOnce("there is no client in this game to hand " + name + "'s messages to");
        }
    }

    /** From the client to the server: handed to the server of this same game. */
    public <MSG> void sendToServer(MSG message) {
        BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler = handlerOf(message);

        if (handler == null) {
            return;
        }

        try {
            if (!Client.toServer(sender -> deliver(handler, message,
                    new NetworkEvent.Context(NetworkDirection.PLAY_TO_SERVER, sender)))) {
                warnOnce(name + "'s messages cannot reach a server on another computer");
            }
        } catch (LinkageError e) {
            warnOnce("there is no client in this game to send " + name + "'s messages from");
        }
    }

    @SuppressWarnings("unchecked")
    private <MSG> BiConsumer<MSG, Supplier<NetworkEvent.Context>> handlerOf(MSG message) {
        if (message == null) {
            return null;
        }

        IndexedMessageCodec.MessageHandler<?> found = handlers.get(message.getClass());
        return found == null ? null : ((IndexedMessageCodec.MessageHandler<MSG>) found).handler();
    }

    private <MSG> void deliver(BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler, MSG message,
            NetworkEvent.Context context) {
        try {
            handler.accept(message, () -> context);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("{}'s handler for {} failed: {}", name, message.getClass().getSimpleName(), e.toString());
        }
    }

    private void warnOnce(String message) {
        if (!warned) {
            warned = true;
            LOGGER.warn(message);
        }
    }

    /** The client's half, in a class of its own so that a dedicated server never loads it. */
    private static final class Client {
        static void run(Runnable work) {
            Minecraft.getInstance().execute(work);
        }

        static boolean toServer(java.util.function.Consumer<ServerPlayer> work) {
            Minecraft client = Minecraft.getInstance();
            MinecraftServer server = client.getSingleplayerServer();

            if (server == null || client.player == null) {
                return false;
            }

            java.util.UUID id = client.player.getUUID();
            server.execute(() -> {
                ServerPlayer sender = server.getPlayerList().getPlayer(id);

                if (sender != null) {
                    work.accept(sender);
                }
            });
            return true;
        }
    }
}
