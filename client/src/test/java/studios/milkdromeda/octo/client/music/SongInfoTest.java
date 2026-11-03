package studios.milkdromeda.octo.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The now-playing card's facts, read from tags made by ffmpeg and from pictures beside a song. */
class SongInfoTest {
    @TempDir
    Path folder;

    private Path fixture(String name) throws IOException {
        Path file = folder.resolve(name);

        try (InputStream in = SongInfoTest.class.getResourceAsStream("/music/" + name)) {
            Files.copy(in, file);
        }

        return file;
    }

    private static int red(int argb) {
        return argb >> 16 & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }

    private static byte[] png(int width, int height, int argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void mp3TagsAndCover() throws IOException {
        // ID3v2.3, with a 64×64 red front cover.
        SongInfo info = SongInfo.read(fixture("tagged.mp3"), "tagged");
        assertEquals("Tagged", info.title());
        assertNotNull(info.cover());
        assertEquals(SongInfo.COVER * SongInfo.COVER, info.cover().length);
        assertTrue(red(info.cover()[0]) > 200 && blue(info.cover()[0]) < 40, "red");
        assertTrue(Math.abs(info.millis() - 500) < 80, "about half a second: " + info.millis());
    }

    @Test
    void id3v24InUtf8WithTheAlbumArtistToFallBackOn() throws IOException {
        SongInfo info = SongInfo.read(fixture("tagged24.mp3"), "file name");
        assertEquals("Café Song", info.title());
        assertEquals("Zoë", info.artist());
        assertNull(info.cover());
    }

    @Test
    void flacCommentsPictureAndLength() throws IOException {
        SongInfo info = SongInfo.read(fixture("tagged.flac"), "tagged");
        assertEquals("Deep Blue", info.title());
        assertEquals("Octo Band", info.artist());
        assertEquals("Tides", info.album());
        assertEquals(250, info.millis());
        assertNotNull(info.cover(), "the attached picture");
        int middle = info.cover()[SongInfo.COVER * SongInfo.COVER / 2 + SongInfo.COVER / 2];
        assertTrue(blue(middle) > 180 && red(middle) < 60, "blue: " + Integer.toHexString(middle));
    }

    @Test
    void oggCommentsAndLength() throws IOException {
        SongInfo info = SongInfo.read(fixture("tagged.ogg"), "tagged");
        assertEquals("Ogg Title", info.title());
        assertEquals("Ogg Artist", info.artist());
        assertEquals("Ogg Album", info.album());
        assertTrue(Math.abs(info.millis() - 300) < 30, "about 0.3 s: " + info.millis());
    }

    @Test
    void oggCoverInAComment() throws IOException {
        // METADATA_BLOCK_PICTURE: a FLAC picture block, base64'd.
        byte[] picture = png(10, 10, 0xFF00FF00);
        byte[] mime = "image/png".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer block = ByteBuffer.allocate(32 + mime.length + picture.length).order(ByteOrder.BIG_ENDIAN);
        block.putInt(3).putInt(mime.length).put(mime).putInt(0).putInt(10).putInt(10).putInt(32).putInt(0)
                .putInt(picture.length).put(picture);
        String comment = "METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(block.array());
        byte[] text = comment.getBytes(StandardCharsets.UTF_8);
        byte[] title = "TITLE=Green".getBytes(StandardCharsets.UTF_8);
        ByteBuffer comments = ByteBuffer.allocate(4 + 4 + 4 + text.length + 4 + title.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        comments.putInt(0).putInt(2).putInt(text.length).put(text).putInt(title.length).put(title);

        SongInfo.Found found = new SongInfo.Found();
        SongInfo.vorbisComments(comments.array(), 0, found);
        assertEquals("Green", found.title);
        int[] cover = SongInfo.picture(found.picture);
        assertNotNull(cover);
        assertTrue((cover[0] >> 8 & 0xFF) > 200, "green");
    }

    @Test
    void aPictureBesideTheSong() throws IOException {
        Path song = fixture("plain.mp3");
        assertNull(SongInfo.read(song, "plain").cover(), "no picture yet");

        Files.write(folder.resolve("Cover.PNG"), png(80, 40, 0xFF0000FF));
        int[] folderCover = SongInfo.read(song, "plain").cover();
        assertNotNull(folderCover, "the folder's cover");
        assertTrue(blue(folderCover[0]) > 200);

        // One named like the song wins over the folder's.
        Files.write(folder.resolve("plain.jpg"), jpeg());
        int[] own = SongInfo.read(song, "plain").cover();
        assertTrue(red(own[0]) > 200, "its own picture: " + Integer.toHexString(own[0]));
    }

    private static byte[] jpeg() throws IOException {
        BufferedImage image = new BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                image.setRGB(x, y, 0xFF0000);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void wavHasALengthAndTheFileNameForATitle() throws IOException {
        byte[] raw = new byte[22050 * 4];
        Path wav = folder.resolve("my_song-name.wav");
        javax.sound.sampled.AudioSystem.write(new javax.sound.sampled.AudioInputStream(
                new java.io.ByteArrayInputStream(raw), new javax.sound.sampled.AudioFormat(22050, 16, 2, true, false),
                22050), javax.sound.sampled.AudioFileFormat.Type.WAVE, wav.toFile());
        SongInfo info = SongInfo.read(wav, MusicPlayer.title(wav));
        assertEquals("my song name", info.title());
        assertEquals(1000, info.millis());
        assertNull(info.artist());
    }

    @Test
    void brokenTagsStillPlayUnderTheFileName() throws IOException {
        Path junk = folder.resolve("broken.mp3");
        Files.write(junk, new byte[] { 'I', 'D', '3', 3, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F, 1, 2, 3 });
        SongInfo info = SongInfo.read(junk, "broken");
        assertEquals("broken", info.title());
        assertNull(info.cover());
    }
}
