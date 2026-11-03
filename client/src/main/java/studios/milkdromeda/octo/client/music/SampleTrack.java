package studios.milkdromeda.octo.client.music;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A track whose decoder hands over 16-bit samples a stretch at a time,
 * interleaved by channel. More than two channels (a 5.1 FLAC) are folded down
 * to stereo: front left and right, with the centre in both.
 */
abstract class SampleTrack implements Track {
    /** The decoder's latest stretch, as {@link #decode()} left it. */
    protected short[] samples = new short[0];
    private int at;
    private int end;
    private boolean over;
    private ByteBuffer out;

    /** How many channels the file has, which may be more than {@link #channels()}. */
    protected abstract int sourceChannels();

    /**
     * Decodes the next stretch into {@link #samples}.
     *
     * @return how many samples it holds (a whole number of frames), or -1 at the end
     */
    protected abstract int decode() throws IOException;

    /** For a decoder that has already decoded a stretch to learn the format: plays it first. */
    protected final void keep(int count) {
        at = 0;
        end = Math.max(count, 0);
    }

    @Override
    public int channels() {
        return Math.min(sourceChannels(), 2);
    }

    @Override
    public ByteBuffer read(int bytes) throws IOException {
        int frame = channels() * 2;

        if (out == null || out.capacity() < bytes) {
            out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }

        out.clear().limit(bytes - bytes % frame);
        int in = sourceChannels();

        while (out.remaining() >= frame) {
            if (at >= end) {
                int count = over ? -1 : decode();

                if (count < 0) {
                    over = true;
                    break;
                }

                at = 0;
                end = count;
                continue;
            }

            if (in <= 2) {
                for (int c = 0; c < in; c++) {
                    out.putShort(samples[at++]);
                }
            } else {
                int centre = samples[at + 2] / 2;
                out.putShort(clamp(samples[at] + centre));
                out.putShort(clamp(samples[at + 1] + centre));
                at += in;
            }
        }

        out.flip();
        return out.hasRemaining() ? out : null;
    }

    static short clamp(long value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }
}
