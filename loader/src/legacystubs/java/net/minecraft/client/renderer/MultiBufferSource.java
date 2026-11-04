package net.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;

/**
 * Compile-time shape of the buffer source the game had until 26.x, which the
 * old {@code GuiGraphics} hands out; at runtime it is the class added back
 * from the release a mod was built for.
 */
public interface MultiBufferSource {
    static BufferSource immediate(ByteBufferBuilder buffer) {
        throw new UnsupportedOperationException();
    }

    class BufferSource implements MultiBufferSource {
        public native void endBatch();
    }
}
