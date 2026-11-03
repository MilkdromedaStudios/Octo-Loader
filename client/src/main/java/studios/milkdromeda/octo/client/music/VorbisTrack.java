package studios.milkdromeda.octo.client.music;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import net.minecraft.client.sounds.JOrbisAudioStream;

/** Ogg Vorbis, with the decoder Minecraft plays its own music with. */
final class VorbisTrack implements Track {
    private final JOrbisAudioStream stream;

    VorbisTrack(InputStream in) throws IOException {
        stream = new JOrbisAudioStream(in);

        if (stream.getFormat().getChannels() > 2) {
            stream.close();
            throw new IOException("an Ogg file with more than two channels");
        }
    }

    @Override
    public int channels() {
        return stream.getFormat().getChannels();
    }

    @Override
    public int rate() {
        return (int) stream.getFormat().getSampleRate();
    }

    @Override
    public ByteBuffer read(int bytes) throws IOException {
        ByteBuffer data = stream.read(bytes);
        return data == null || !data.hasRemaining() ? null : data;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
