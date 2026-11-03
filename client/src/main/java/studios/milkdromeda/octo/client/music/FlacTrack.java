package studios.milkdromeda.octo.client.music;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * FLAC, decoded here: lossless, so every sample comes out exactly as it went
 * in, then brought to 16 bits. Follows the format as RFC 9639 sets it out:
 * constant, verbatim, fixed and LPC subframes, Rice-coded residuals, and the
 * three ways stereo is stored.
 */
final class FlacTrack extends SampleTrack {
    private final Bits in;
    private final int channels;
    private final int rate;
    private final int bitsPerSample;
    private long[][] block = new long[0][];

    FlacTrack(InputStream stream) throws IOException {
        in = new Bits(stream);
        long magic = in.read(32);

        // An ID3 tag some taggers put in front.
        if ((magic >>> 8) == 0x494433) {
            in.read(8);
            int flags = (int) in.read(8);
            long size = in.read(32);
            size = (size & 0x7F) | (size >> 8 & 0x7F) << 7 | (size >> 16 & 0x7F) << 14 | (size >> 24 & 0x7F) << 21;
            in.skip(size + ((flags & 0x10) != 0 ? 10 : 0));
            magic = in.read(32);
        }

        if (magic != 0x664C6143L) {
            throw new IOException("not a FLAC file");
        }

        int foundRate = 0;
        int foundChannels = 0;
        int foundBits = 0;
        boolean last;

        do {
            last = in.read(1) == 1;
            int type = (int) in.read(7);
            long length = in.read(24);

            if (type == 0) {
                // STREAMINFO: block and frame sizes, then the format.
                in.read(16);
                in.read(16);
                in.read(24);
                in.read(24);
                foundRate = (int) in.read(20);
                foundChannels = (int) in.read(3) + 1;
                foundBits = (int) in.read(5) + 1;
                in.read(36);
                in.skip(16 + length - 34);
            } else {
                in.skip(length);
            }
        } while (!last);

        if (foundRate == 0 || foundChannels == 0) {
            throw new IOException("a FLAC file without its stream information");
        }

        rate = foundRate;
        channels = foundChannels;
        bitsPerSample = foundBits;
    }

    @Override
    protected int sourceChannels() {
        return channels;
    }

    @Override
    public int rate() {
        return rate;
    }

    @Override
    protected int decode() throws IOException {
        try {
            return frame();
        } catch (EOFException e) {
            return -1;
        }
    }

    /** One frame: its header, a subframe a channel, and the footer. */
    private int frame() throws IOException {
        // Frames start on a byte with the 14-bit sync code 0b11111111111110.
        in.align();
        int previous = in.nextByte();

        while (true) {
            if (previous < 0) {
                return -1;
            }

            int current = in.nextByte();

            if (current < 0) {
                return -1;
            }

            if (previous == 0xFF && (current & 0xFE) == 0xF8) {
                break;
            }

            previous = current;
        }

        int blockSizeCode = (int) in.read(4);
        int rateCode = (int) in.read(4);
        int assignment = (int) in.read(4);
        int sizeCode = (int) in.read(3);
        in.read(1);

        // The frame or sample number, UTF-8 style.
        int lead = (int) in.read(8);
        int more = lead < 0x80 ? 0 : lead >= 0xFE ? 6 : lead >= 0xFC ? 5 : lead >= 0xF8 ? 4 : lead >= 0xF0 ? 3
                : lead >= 0xE0 ? 2 : lead >= 0xC0 ? 1 : -1;

        if (more < 0) {
            throw new IOException("a damaged FLAC frame header");
        }

        in.read(8 * more);

        int blockSize = switch (blockSizeCode) {
            case 1 -> 192;
            case 2, 3, 4, 5 -> 576 << (blockSizeCode - 2);
            case 6 -> (int) in.read(8) + 1;
            case 7 -> (int) in.read(16) + 1;
            case 8, 9, 10, 11, 12, 13, 14, 15 -> 256 << (blockSizeCode - 8);
            default -> throw new IOException("a FLAC frame with a reserved block size");
        };

        if (rateCode == 12) {
            in.read(8);
        } else if (rateCode == 13 || rateCode == 14) {
            in.read(16);
        }

        in.read(8); // header CRC

        int bits = switch (sizeCode) {
            case 0 -> bitsPerSample;
            case 1 -> 8;
            case 2 -> 12;
            case 4 -> 16;
            case 5 -> 20;
            case 6 -> 24;
            case 7 -> 32;
            default -> throw new IOException("a FLAC frame with a reserved sample size");
        };

        int frameChannels = assignment < 8 ? assignment + 1 : assignment <= 10 ? 2 : -1;

        if (frameChannels != channels) {
            throw new IOException("a FLAC frame that does not match the stream");
        }

        if (block.length != channels || block[0].length < blockSize) {
            block = new long[channels][blockSize];
        }

        for (int c = 0; c < channels; c++) {
            // The side channel of a stereo pair has one bit more.
            boolean side = (assignment == 8 && c == 1) || (assignment == 9 && c == 0) || (assignment == 10 && c == 1);
            subframe(block[c], blockSize, bits + (side ? 1 : 0));
        }

        in.align();
        in.read(16); // frame CRC

        long[] a = channels > 1 ? block[0] : null;
        long[] b = channels > 1 ? block[1] : null;

        switch (assignment) {
            case 8 -> { // left, side
                for (int i = 0; i < blockSize; i++) {
                    b[i] = a[i] - b[i];
                }
            }
            case 9 -> { // side, right
                for (int i = 0; i < blockSize; i++) {
                    a[i] += b[i];
                }
            }
            case 10 -> { // mid, side
                for (int i = 0; i < blockSize; i++) {
                    long side = b[i];
                    long mid = (a[i] << 1) | (side & 1);
                    a[i] = (mid + side) >> 1;
                    b[i] = (mid - side) >> 1;
                }
            }
            default -> {
                // Channels stored as they are.
            }
        }

        int count = blockSize * channels;

        if (samples.length < count) {
            samples = new short[count];
        }

        int shift = bits - 16;

        for (int i = 0, k = 0; i < blockSize; i++) {
            for (int c = 0; c < channels; c++) {
                long value = block[c][i];
                samples[k++] = clamp(shift >= 0 ? value >> shift : value << -shift);
            }
        }

        return count;
    }

    private void subframe(long[] out, int blockSize, int bits) throws IOException {
        if (in.read(1) != 0) {
            throw new IOException("a damaged FLAC subframe");
        }

        int type = (int) in.read(6);
        int wasted = 0;

        if (in.read(1) == 1) {
            wasted = in.unary() + 1;
            bits -= wasted;
        }

        if (type == 0) {
            long value = in.signed(bits);

            for (int i = 0; i < blockSize; i++) {
                out[i] = value;
            }
        } else if (type == 1) {
            for (int i = 0; i < blockSize; i++) {
                out[i] = in.signed(bits);
            }
        } else if (type >= 8 && type <= 12) {
            int order = type - 8;
            warmUp(out, order, bits);
            residual(out, blockSize, order);
            fixed(out, blockSize, order);
        } else if (type >= 32) {
            int order = (type & 31) + 1;
            warmUp(out, order, bits);
            int precision = (int) in.read(4) + 1;
            int shift = (int) in.signed(5);

            if (precision == 16 || shift < 0) {
                throw new IOException("a damaged FLAC LPC subframe");
            }

            long[] coefficients = new long[order];

            for (int j = 0; j < order; j++) {
                coefficients[j] = in.signed(precision);
            }

            residual(out, blockSize, order);

            for (int i = order; i < blockSize; i++) {
                long sum = 0;

                for (int j = 0; j < order; j++) {
                    sum += coefficients[j] * out[i - 1 - j];
                }

                out[i] += sum >> shift;
            }
        } else {
            throw new IOException("a FLAC subframe of a reserved type");
        }

        if (wasted > 0) {
            for (int i = 0; i < blockSize; i++) {
                out[i] <<= wasted;
            }
        }
    }

    private void warmUp(long[] out, int order, int bits) throws IOException {
        for (int i = 0; i < order; i++) {
            out[i] = in.signed(bits);
        }
    }

    /** The prediction errors, Rice-coded in partitions, into {@code out} after the warm-up samples. */
    private void residual(long[] out, int blockSize, int order) throws IOException {
        int method = (int) in.read(2);

        if (method > 1) {
            throw new IOException("a FLAC residual coded a reserved way");
        }

        int parameterBits = method == 0 ? 4 : 5;
        int escape = method == 0 ? 15 : 31;
        int partitionOrder = (int) in.read(4);
        int each = blockSize >> partitionOrder;

        if (each << partitionOrder != blockSize || each < order) {
            throw new IOException("a damaged FLAC residual");
        }

        int i = order;

        for (int p = 0; p < 1 << partitionOrder; p++) {
            int count = p == 0 ? each - order : each;
            int parameter = (int) in.read(parameterBits);

            if (parameter == escape) {
                int raw = (int) in.read(5);

                for (int n = 0; n < count; n++) {
                    out[i++] = in.signed(raw);
                }
            } else {
                for (int n = 0; n < count; n++) {
                    long folded = ((long) in.unary() << parameter) | in.read(parameter);
                    out[i++] = (folded >>> 1) ^ -(folded & 1);
                }
            }
        }
    }

    private static void fixed(long[] s, int blockSize, int order) {
        switch (order) {
            case 1 -> {
                for (int i = 1; i < blockSize; i++) {
                    s[i] += s[i - 1];
                }
            }
            case 2 -> {
                for (int i = 2; i < blockSize; i++) {
                    s[i] += 2 * s[i - 1] - s[i - 2];
                }
            }
            case 3 -> {
                for (int i = 3; i < blockSize; i++) {
                    s[i] += 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3];
                }
            }
            case 4 -> {
                for (int i = 4; i < blockSize; i++) {
                    s[i] += 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4];
                }
            }
            default -> {
                // Order 0: the residual is the signal.
            }
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** Reads a stream a few bits at a time, most significant first. */
    static final class Bits {
        private final InputStream in;
        private long buffer;
        private int count;

        Bits(InputStream in) {
            this.in = in;
        }

        /** An unsigned number of up to 56 bits. */
        long read(int bits) throws IOException {
            if (bits == 0) {
                return 0;
            }

            while (count < bits) {
                int next = in.read();

                if (next < 0) {
                    throw new EOFException();
                }

                buffer = (buffer << 8) | next;
                count += 8;
            }

            count -= bits;
            return (buffer >>> count) & ((1L << bits) - 1);
        }

        /** A two's-complement number of up to 33 bits. */
        long signed(int bits) throws IOException {
            if (bits == 0) {
                return 0;
            }

            if (bits > 32) {
                long high = read(bits - 32);
                long value = (high << 32) | read(32);
                return (value << (64 - bits)) >> (64 - bits);
            }

            return (read(bits) << (64 - bits)) >> (64 - bits);
        }

        /** How many 0 bits come before the next 1. */
        int unary() throws IOException {
            int zeros = 0;

            while (true) {
                if (count == 0) {
                    int next = in.read();

                    if (next < 0) {
                        throw new EOFException();
                    }

                    buffer = next;
                    count = 8;
                }

                count--;

                if (((buffer >>> count) & 1) != 0) {
                    return zeros;
                }

                zeros++;
            }
        }

        /** Drops what is left of the current byte. */
        void align() {
            count -= count % 8;
        }

        /** The next whole byte, or -1 at the end; call aligned. */
        int nextByte() throws IOException {
            if (count >= 8) {
                count -= 8;
                return (int) (buffer >>> count) & 0xFF;
            }

            return in.read();
        }

        void skip(long bytes) throws IOException {
            align();

            while (bytes > 0 && count >= 8) {
                count -= 8;
                bytes--;
            }

            in.skipNBytes(bytes);
        }

        void close() throws IOException {
            in.close();
        }
    }
}
