package net.minecraftforge.network.simple;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import net.minecraftforge.network.NetworkEvent;

/** What Forge's old channel answered a registration with: the message's handler. */
public class IndexedMessageCodec {
    public static class MessageHandler<MSG> {
        private final int index;
        private final Class<MSG> type;
        private final BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler;

        MessageHandler(int index, Class<MSG> type, BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler) {
            this.index = index;
            this.type = type;
            this.handler = handler;
        }

        public int getIndex() {
            return index;
        }

        public Class<MSG> getMessageType() {
            return type;
        }

        BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler() {
            return handler;
        }
    }
}
