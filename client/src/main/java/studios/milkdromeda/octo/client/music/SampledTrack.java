package studios.milkdromeda.octo.client.music;

import java.io.IOException;
import java.io.InputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * WAV, AIFF and AU, read by Java itself and turned into 16-bit samples
 * whatever they were stored as (8, 24 or 32-bit, floating point, A-law...).
 */
final class SampledTrack extends SampleTrack {
    private final AudioInputStream stream;
    private final int channels;
    private final int rate;
    private final byte[] bytes;

    SampledTrack(InputStream in) throws IOException {
        AudioInputStream source;

        try {
            source = AudioSystem.getAudioInputStream(in);
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("not a sound file Java can read", e);
        }

        AudioFormat format = source.getFormat();
        channels = format.getChannels();
        rate = Math.round(format.getSampleRate());
        AudioFormat wanted = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, format.getSampleRate(), 16, channels,
                channels * 2, format.getSampleRate(), false);

        try {
            stream = AudioSystem.getAudioInputStream(wanted, source);
        } catch (IllegalArgumentException e) {
            source.close();
            throw new IOException("its sound is stored as " + format + ", which Java cannot convert", e);
        }

        bytes = new byte[channels * 2 * 4096];
        samples = new short[channels * 4096];
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
        // An AudioInputStream reads whole frames only.
        int read = stream.read(bytes, 0, bytes.length);

        if (read <= 0) {
            return -1;
        }

        int count = read / 2;

        for (int i = 0; i < count; i++) {
            samples[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
        }

        return count;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
