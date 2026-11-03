package studios.milkdromeda.octo.client.music;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every kind of file the music folder takes, decoded as the player would. The
 * fixtures were made with ffmpeg from one signal (440 Hz on the left; 660 Hz
 * and a little noise on the right; 22050 Hz), kept raw beside them, so a
 * lossless format has to come out exactly as that and a lossy one has to keep
 * each tone on its own side.
 */
class TrackTest {
    @TempDir
    Path folder;

    private Path fixture(String name) throws IOException {
        Path file = folder.resolve(name);

        try (InputStream in = TrackTest.class.getResourceAsStream("/music/" + name)) {
            Files.copy(in, file);
        }

        return file;
    }

    private byte[] bytes(String name) throws IOException {
        try (InputStream in = TrackTest.class.getResourceAsStream("/music/" + name)) {
            return in.readAllBytes();
        }
    }

    private short[] reference16() throws IOException {
        byte[] raw = bytes("ref16.raw");
        short[] out = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    /** The 24-bit reference, as the 16 bits the player keeps. */
    private short[] reference24() throws IOException {
        byte[] raw = bytes("ref24.raw");
        short[] out = new short[raw.length / 3];

        for (int i = 0; i < out.length; i++) {
            int value = (raw[3 * i] & 0xFF) | (raw[3 * i + 1] & 0xFF) << 8 | raw[3 * i + 2] << 16;
            out[i] = (short) (value >> 8);
        }

        return out;
    }

    private static short[] decode(Track track) throws IOException {
        short[] all = new short[0];
        int length = 0;

        try (track) {
            ByteBuffer chunk;

            // An odd size, so reads stop part-way through a decoded stretch.
            while ((chunk = track.read(10_002)) != null) {
                assertEquals(ByteOrder.nativeOrder(), chunk.order());
                int count = chunk.remaining() / 2;
                assertEquals(0, count % track.channels(), "whole frames only");

                if (length + count > all.length) {
                    all = Arrays.copyOf(all, Math.max(all.length * 2, length + count));
                }

                chunk.asShortBuffer().get(all, length, count);
                length += count;
            }

            assertEquals(null, track.read(10_002), "stays over");
        }

        return Arrays.copyOf(all, length);
    }

    /** How strongly one channel holds a tone (Goertzel). */
    private static double tone(short[] samples, int channel, int channels, int rate, double frequency) {
        double coefficient = 2 * Math.cos(2 * Math.PI * frequency / rate);
        double s1 = 0;
        double s2 = 0;

        for (int i = channel; i < samples.length; i += channels) {
            double s0 = samples[i] + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        return s1 * s1 + s2 * s2 - coefficient * s1 * s2;
    }

    private static void assertTonesOnTheirSides(short[] samples, int rate) {
        assertTrue(tone(samples, 0, 2, rate, 440) > 50 * tone(samples, 0, 2, rate, 660), "440 Hz on the left");
        assertTrue(tone(samples, 1, 2, rate, 660) > 50 * tone(samples, 1, 2, rate, 440), "660 Hz on the right");
    }

    @Test
    void flacComesOutExactlyAsItWentIn() throws IOException {
        short[] reference = reference16();

        for (String name : List.of("fixed.flac", "lpc.flac")) {
            Track track = Track.open(fixture(name));
            assertEquals(FlacTrack.class, track.getClass());
            assertEquals(2, track.channels());
            assertEquals(22050, track.rate());
            assertArrayEquals(reference, decode(track), name);
        }
    }

    @Test
    void flacStereoEveryWayItIsStored() throws IOException {
        short[] reference = reference16();

        for (String mode : List.of("indep", "left_side", "right_side", "mid_side")) {
            short[] decoded = decode(Track.open(fixture("stereo-" + mode + ".flac")));
            assertEquals((int) (0.12 * 22050) * 2, decoded.length, mode);
            assertArrayEquals(Arrays.copyOf(reference, decoded.length), decoded, mode);
        }
    }

    @Test
    void flacOf24BitsKeepsTheTop16() throws IOException {
        Track track = Track.open(fixture("mono24.flac"));
        assertEquals(1, track.channels());
        assertEquals(48000, track.rate());
        assertArrayEquals(reference24(), decode(track));
    }

    @Test
    void surroundFlacFoldsDownToStereo() throws IOException {
        Track track = Track.open(fixture("surround.flac"));
        assertEquals(2, track.channels());
        assertEquals(8000, track.rate());
        short[] decoded = decode(track);
        assertEquals(1600 * 2, decoded.length);
        // Front left (440 Hz) and the centre (660 Hz) on the left; the back speakers are left out.
        assertTrue(tone(decoded, 0, 2, 8000, 440) > 0 && tone(decoded, 0, 2, 8000, 660) > 0);
        assertTrue(tone(decoded, 0, 2, 8000, 440) > 50 * tone(decoded, 0, 2, 8000, 770));
    }

    @Test
    void wavAndAiffAsJavaReadsThem() throws IOException {
        short[] reference = reference16();
        byte[] raw = bytes("ref16.raw");
        AudioFormat little = new AudioFormat(22050, 16, 2, true, false);

        Path wav = folder.resolve("song.wav");
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(raw), little, raw.length / 4),
                AudioFileFormat.Type.WAVE, wav.toFile());
        Track track = Track.open(wav);
        assertEquals(SampledTrack.class, track.getClass());
        assertEquals(22050, track.rate());
        assertArrayEquals(reference, decode(track), "16-bit WAV");

        // AIFF stores its samples big-endian.
        byte[] big = raw.clone();

        for (int i = 0; i < big.length; i += 2) {
            byte b = big[i];
            big[i] = big[i + 1];
            big[i + 1] = b;
        }

        Path aiff = folder.resolve("song.aiff");
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(big), new AudioFormat(22050, 16, 2, true,
                true), big.length / 4), AudioFileFormat.Type.AIFF, aiff.toFile());
        assertArrayEquals(reference, decode(Track.open(aiff)), "AIFF");

        // 24-bit WAV comes down to 16 bits.
        byte[] raw24 = bytes("ref24.raw");
        Path wav24 = folder.resolve("deep.wav");
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(raw24), new AudioFormat(48000, 24, 1, true,
                false), raw24.length / 3), AudioFileFormat.Type.WAVE, wav24.toFile());
        short[] expected = reference24();
        short[] decoded = decode(Track.open(wav24));
        assertEquals(expected.length, decoded.length);

        for (int i = 0; i < expected.length; i++) {
            assertTrue(Math.abs(expected[i] - decoded[i]) <= 1, "24-bit WAV sample " + i);
        }
    }

    @Test
    void mp3AtEveryRate() throws IOException {
        // 22050 Hz is MPEG-2, which a decoder for MPEG-1 alone would play at twice the speed.
        Track track = Track.open(fixture("mpeg2.mp3"));
        assertEquals(Mp3Track.class, track.getClass());
        assertEquals(2, track.channels());
        assertEquals(22050, track.rate());
        short[] decoded = decode(track);
        assertTrue(Math.abs(decoded.length / 2 - 11025) < 2500, "about half a second: " + decoded.length / 2);
        assertTonesOnTheirSides(decoded, 22050);

        Track plain = Track.open(fixture("plain.mp3"));
        assertEquals(44100, plain.rate());
        short[] untagged = decode(plain);
        assertTrue(tone(untagged, 0, 2, 44100, 440) > 50 * tone(untagged, 0, 2, 44100, 660));
    }

    @Test
    void mp3TagsAndCoverArtAreSteppedOver() throws IOException {
        assertTrue(new String(bytes("tagged.mp3"), 0, 3).equals("ID3"));
        // The same audio frames with a tag and a picture in front: the same sound.
        assertArrayEquals(decode(Track.open(fixture("plain.mp3"))), decode(Track.open(fixture("tagged.mp3"))));
    }

    @Test
    void oggWithTheGamesOwnDecoder() throws IOException {
        Track track = Track.open(fixture("vorbis.ogg"));
        assertEquals(VorbisTrack.class, track.getClass());
        assertEquals(2, track.channels());
        assertEquals(22050, track.rate());
        short[] decoded = decode(track);
        assertTrue(Math.abs(decoded.length / 2 - 11025) < 1000, "about half a second: " + decoded.length / 2);
        assertTonesOnTheirSides(decoded, 22050);
    }

    @Test
    void namesDoNotFoolIt() throws IOException {
        // An Ogg file named .mp3 plays as what it is.
        Path misnamed = folder.resolve("actually-ogg.mp3");
        Files.write(misnamed, bytes("vorbis.ogg"));
        try (Track track = Track.open(misnamed)) {
            assertEquals(VorbisTrack.class, track.getClass());
        }

        // Something that is not music is turned down, not played as noise.
        Path text = folder.resolve("notes.flac");
        Files.writeString(text, "these are not the songs you are looking for");
        assertThrows(IOException.class, () -> Track.open(text));

        Path empty = folder.resolve("empty.mp3");
        Files.write(empty, new byte[0]);
        assertThrows(IOException.class, () -> Track.open(empty));
    }

    @Test
    void theFolderIsSearchedForEveryKind() throws IOException {
        for (String name : List.of("a.mp3", "b.OGG", "c.flac", "d.wav", "e.aiff", "f.txt", "g.m4a", "cover.jpg")) {
            Files.writeString(folder.resolve(name), "x");
        }

        Files.createDirectory(folder.resolve("album.mp3"));
        List<String> found = MusicPlayer.scan(folder).stream().map(path -> path.getFileName().toString()).toList();
        assertEquals(List.of("a.mp3", "b.OGG", "c.flac", "d.wav", "e.aiff"), found);
    }
}
