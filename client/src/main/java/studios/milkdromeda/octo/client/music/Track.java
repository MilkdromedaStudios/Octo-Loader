package studios.milkdromeda.octo.client.music;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * A song opened for playing, decoded as it plays: 16-bit PCM in the machine's
 * own byte order, mono or stereo, which is what OpenAL takes.
 */
interface Track extends Closeable {
    /** The file endings the music folder is searched for. */
    Set<String> ENDINGS = Set.of("ogg", "oga", "mp3", "flac", "wav", "aif", "aiff", "aifc", "au");

    /** 1 or 2. */
    int channels();

    /** Samples a second. */
    int rate();

    /**
     * Up to {@code bytes} more of the song, in whole samples.
     *
     * @return a buffer good until the next call, or {@code null} once the song is over
     */
    ByteBuffer read(int bytes) throws IOException;

    static boolean playable(Path file) {
        return ENDINGS.contains(ending(file));
    }

    static String ending(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Opens a song by what it is: its first bytes say, and its name when they
     * don't (an MP3 can start straight with audio).
     */
    static Track open(Path file) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);

        try {
            in.mark(16);
            byte[] head = in.readNBytes(12);
            in.reset();
            String ending = ending(file);

            if (starts(head, "OggS")) {
                return new VorbisTrack(in);
            }

            if (starts(head, "fLaC") || (starts(head, "ID3") && ending.equals("flac"))) {
                return new FlacTrack(in);
            }

            if (starts(head, "RIFF") || starts(head, "FORM") || starts(head, ".snd")) {
                return new SampledTrack(in);
            }

            if (starts(head, "ID3") || (head.length > 1 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0)) {
                return new Mp3Track(in);
            }

            return switch (ending) {
                case "mp3" -> new Mp3Track(in);
                case "flac" -> new FlacTrack(in);
                case "ogg", "oga" -> new VorbisTrack(in);
                case "wav", "aif", "aiff", "aifc", "au" -> new SampledTrack(in);
                default -> throw new IOException("not a kind of file Octo can play");
            };
        } catch (IOException e) {
            in.close();
            throw e;
        } catch (RuntimeException e) {
            in.close();
            throw new IOException(e.toString(), e);
        } catch (LinkageError e) {
            in.close();
            throw e;
        }
    }

    private static boolean starts(byte[] head, String magic) {
        if (head.length < magic.length()) {
            return false;
        }

        for (int i = 0; i < magic.length(); i++) {
            if (head[i] != (byte) magic.charAt(i)) {
                return false;
            }
        }

        return true;
    }
}
