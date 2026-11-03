package studios.milkdromeda.octo.client.music;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.lwjgl.openal.AL10;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;

/**
 * The player's own music: the MP3, Ogg, FLAC, WAV and AIFF files in
 * {@code <game>/octo/music}, played in place of Minecraft's music, decoded as
 * they play and streamed through the game's OpenAL device on a thread of its
 * own, a few buffers ahead.
 */
public final class MusicPlayer {
    private static final MusicPlayer INSTANCE = new MusicPlayer();
    private static final int BUFFERS = 4;
    private static final int CHUNK = 64 * 1024;

    private volatile List<Path> songs = List.of();
    /** Songs that would not open, with when they were last changed: left out until they change. */
    private final Map<Path, Long> unplayable = new ConcurrentHashMap<>();
    private volatile String nowPlaying;
    /** What the now-playing card shows: the file's name at first, then its tags and cover. */
    private volatile SongInfo info;
    private volatile long startedAt;
    private volatile boolean skip;
    private volatile float gain;
    private volatile boolean wanted;
    private int order;
    private long lastScan;
    /** When the music thread may next start: later, after one that failed (no sound device, say). */
    private volatile long retryAfter;
    /** Whether the game's sound device is open: before it is, OpenAL must not be touched at all. */
    private volatile boolean deviceReady;
    private Thread thread;
    private Path folder;

    private MusicPlayer() {
    }

    public static MusicPlayer get() {
        return INSTANCE;
    }

    public static Path folder(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("octo").resolve("music");
    }

    /** Opens the music folder in the system's file browser, making it first. */
    public static void openFolder(Minecraft minecraft) {
        Path folder = folder(minecraft);

        try {
            Files.createDirectories(folder);
            org.lwjgl.sdl.SDLMisc.SDL_OpenURL(folder.toUri().toString());
        } catch (IOException | RuntimeException | LinkageError e) {
            OctoClient.LOG.warn("could not open {}", folder, e);
        }
    }

    /** Whether Octo's music is what plays now: on, with songs to play, and a sound device to play them on. */
    public boolean active() {
        return wanted && deviceReady && !songs.isEmpty();
    }

    /**
     * Called as the game's sound device opens and closes. The music stops
     * with it (its thread ends at its next step) and starts again when it is
     * back.
     */
    public void deviceReady(boolean ready) {
        deviceReady = ready;

        if (!ready) {
            Thread running = thread;

            if (running != null) {
                // Waits for the thread to let go of its sources before the device goes.
                try {
                    running.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public String nowPlaying() {
        SongInfo song = info();
        return song == null ? null : song.title();
    }

    /** The song playing now, or {@code null}. */
    public SongInfo info() {
        return active() && nowPlaying != null ? info : null;
    }

    public long startedAt() {
        return startedAt;
    }

    public int songCount() {
        return songs.size();
    }

    public void next() {
        skip = true;
    }

    /** Every game tick: the settings, the volume, and a fresh look at the folder now and then. */
    public void tick(Minecraft minecraft) {
        Config config = OctoClient.config();
        wanted = config.music;
        gain = config.musicVolume / 100f * minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
        folder = folder(minecraft);
        long now = System.currentTimeMillis();

        if (now - lastScan > (wanted ? 5000 : 30000)) {
            lastScan = now;
            songs = scan(folder);
            unplayable.entrySet().removeIf(entry -> modified(entry.getKey()) != entry.getValue());
        }

        if (active() && now >= retryAfter && (thread == null || !thread.isAlive())) {
            thread = new Thread(this::run, "Octo music");
            thread.setDaemon(true);
            thread.start();
        }
    }

    static List<Path> scan(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }

        try (var files = Files.list(folder)) {
            return files.filter(file -> Files.isRegularFile(file) && Track.playable(file)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /** "my_song-name.ogg" to "my song name". */
    static String title(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').replace('-', ' ').strip();
    }

    private Path pick() {
        List<Path> list = new ArrayList<>(songs);
        list.removeAll(unplayable.keySet());

        if (list.isEmpty()) {
            return null;
        }

        if (OctoClient.config().shuffle) {
            Collections.shuffle(list, ThreadLocalRandom.current());
            return list.get(0);
        }

        return list.get(Math.floorMod(order++, list.size()));
    }

    /** The music thread: one song after another while music is wanted. */
    private void run() {
        int source = 0;
        int[] buffers = new int[BUFFERS];

        try {
            source = AL10.alGenSources();
            AL10.alGenBuffers(buffers);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);

            while (active()) {
                Path song = pick();

                if (song == null) {
                    // Nothing in the folder would play: looked at again in a while, not every tick.
                    retryAfter = System.currentTimeMillis() + 10_000;
                    break;
                }

                try {
                    play(source, buffers, song);
                } catch (IOException | RuntimeException | LinkageError e) {
                    // That song, not the music: the next one plays. One that would not even
                    // open is left out until the folder is looked at afresh.
                    OctoClient.LOG.warn("Octo music could not play {}: {}", song.getFileName(), e.toString());

                    if (nowPlaying == null) {
                        unplayable.put(song, modified(song));
                    }
                }
            }
        } catch (Throwable e) {
            // Tried again in half a minute, not every tick.
            retryAfter = System.currentTimeMillis() + 30_000;
            OctoClient.LOG.warn("Octo music stopped: {}", e.toString());
        } finally {
            nowPlaying = null;

            try {
                if (source != 0) {
                    AL10.alSourceStop(source);
                    AL10.alSourcei(source, AL10.AL_BUFFER, 0);
                    AL10.alDeleteSources(source);
                    AL10.alDeleteBuffers(buffers);
                }
            } catch (Throwable e) {
                // The sound device went away with the game.
            }
        }
    }

    private void play(int source, int[] buffers, Path song) throws IOException, InterruptedException {
        skip = false;
        nowPlaying = null;

        try (Track stream = Track.open(song)) {
            int alFormat = stream.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            int rate = stream.rate();
            info = SongInfo.named(title(song));
            nowPlaying = title(song);
            startedAt = System.currentTimeMillis();
            OctoClient.LOG.info("Octo music: playing {}", song.getFileName());
            boolean ended = false;

            // Fill every buffer, then start.
            int queued = 0;

            for (int buffer : buffers) {
                if (!fill(stream, buffer, alFormat, rate)) {
                    ended = true;
                    break;
                }

                AL10.alSourceQueueBuffers(source, buffer);
                queued++;
            }

            AL10.alSourcef(source, AL10.AL_GAIN, gain);
            AL10.alSourcePlay(source);

            // The tags and cover once it is playing, so a big cover never delays the song.
            info = SongInfo.read(song, title(song));

            while (queued > 0 && active() && !skip) {
                AL10.alSourcef(source, AL10.AL_GAIN, gain);
                int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);

                while (processed-- > 0) {
                    int buffer = AL10.alSourceUnqueueBuffers(source);
                    queued--;

                    if (!ended && fill(stream, buffer, alFormat, rate)) {
                        AL10.alSourceQueueBuffers(source, buffer);
                        queued++;
                    } else {
                        ended = true;
                    }
                }

                // Restarted if it ran dry while the game was busy.
                if (queued > 0 && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                    AL10.alSourcePlay(source);
                }

                Thread.sleep(50);
            }
        } finally {
            AL10.alSourceStop(source);
            AL10.alSourcei(source, AL10.AL_BUFFER, 0);
        }
    }

    /** Decodes the next chunk into a buffer; false once the song has no more. */
    private static boolean fill(Track stream, int buffer, int format, int rate) throws IOException {
        ByteBuffer data = stream.read(CHUNK);

        if (data == null) {
            return false;
        }

        AL10.alBufferData(buffer, format, data, rate);
        return true;
    }
}
