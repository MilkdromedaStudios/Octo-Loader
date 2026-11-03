package studios.milkdromeda.octo.client.music;

import java.io.IOException;
import java.io.InputStream;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * MP3 (and MP2), decoded with JLayer, which ships inside Octo Client as its
 * own jar. It reads MPEG 1, 2 and 2.5 and steps over ID3 tags and cover art.
 */
final class Mp3Track extends SampleTrack {
    /** Bad frames in a row before the file counts as unreadable rather than scuffed. */
    private static final int BAD_FRAMES = 32;

    private final InputStream in;
    private final Bitstream bitstream;
    private final Decoder decoder = new Decoder();
    private final int channels;
    private final int rate;

    Mp3Track(InputStream in) throws IOException {
        this.in = in;
        bitstream = new Bitstream(in);
        int first = decode();

        if (first < 0) {
            throw new IOException("no MP3 audio in it");
        }

        keep(first);
        channels = decoder.getOutputChannels();
        rate = decoder.getOutputFrequency();
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
        int bad = 0;

        while (true) {
            Header header;

            try {
                header = bitstream.readFrame();
            } catch (BitstreamException e) {
                // A cut-off last frame: the song is over.
                return -1;
            }

            if (header == null) {
                return -1;
            }

            try {
                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                samples = output.getBuffer();
                return output.getBufferLength();
            } catch (DecoderException | RuntimeException e) {
                // One damaged frame is a click; a run of them is not an MP3.
                if (++bad > BAD_FRAMES) {
                    throw new IOException("unreadable MP3 audio", e);
                }
            } finally {
                bitstream.closeFrame();
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            bitstream.close();
        } catch (BitstreamException e) {
            in.close();
        }
    }
}
