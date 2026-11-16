package net.minecraftforge.network;

import java.util.concurrent.CompletableFuture;

import net.minecraft.server.level.ServerPlayer;

/**
 * Forge's network event from 1.13 to 1.20.1, of which mods use the context a
 * message's handler is given: who sent it, which way it went, and a way to
 * run its work on the game's thread.
 */
public class NetworkEvent {
    public static class Context {
        private final NetworkDirection direction;
        private final ServerPlayer sender;
        private boolean handled;

        public Context(NetworkDirection direction, ServerPlayer sender) {
            this.direction = direction;
            this.sender = sender;
        }

        public NetworkDirection getDirection() {
            return direction;
        }

        /** The player a message to the server came from; {@code null} on a client. */
        public ServerPlayer getSender() {
            return sender;
        }

        /** Octo hands a message over on the thread its work belongs on already. */
        public CompletableFuture<Void> enqueueWork(Runnable work) {
            work.run();
            return CompletableFuture.completedFuture(null);
        }

        public void setPacketHandled(boolean handled) {
            this.handled = handled;
        }

        public boolean getPacketHandled() {
            return handled;
        }
    }
}
