package studios.milkdromeda.octo.client.music;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;

/**
 * What the now-playing card shows about a song: its title, artist and album,
 * how long it is, and its cover. These come from the file's own tags (ID3 in an
 * MP3, Vorbis comments and pictures in FLAC and Ogg), and the cover, failing
 * that, from a picture beside it: one named like the song, or the folder's
 * {@code cover}, {@code folder}, {@code front} or {@code album} image.
 *
 * @param title  never null: the file's name when it has no title tag
 * @param millis how long the song is, or -1 when that can't be told
 * @param cover  {@link #COVER} × {@link #COVER} pixels, ARGB, or {@code null}
 */
public record SongInfo(String title, String artist, String album, long millis, int[] cover) {
    /** The cover's width and height, in pixels. */
    public static final int COVER = 64;

    /** Tags larger than this are not read: a cover is never that big. */
    private static final int MOST = 32 << 20;
    private static final List<String> PICTURE_NAMES = List.of("cover", "folder", "front", "album");
    private static final List<String> PICTURE_ENDINGS = List.of("png", "jpg", "jpeg");

    /** Just the name, for the moment before the tags are read. */
    public static SongInfo named(String title) {
        return new SongInfo(title, null, null, -1, null);
    }

    /** Never fails: whatever cannot be read is left out. */
    public static SongInfo read(Path file, String fallbackTitle) {
        Found found = new Found();

        try {
            switch (Track.ending(file)) {
                case "mp3" -> mp3(file, found);
                case "flac" -> flac(file, found);
                case "ogg", "oga" -> ogg(file, found);
                case "wav", "aif", "aiff", "aifc", "au" -> sampled(file, found);
                default -> {
                    // Nothing more to read.
                }
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            // Unreadable tags: the song still plays, with its file name.
        }

        int[] cover = found.picture == null ? null : picture(found.picture);

        if (cover == null) {
            cover = besideIt(file);
        }

        return new SongInfo(blank(found.title) ? fallbackTitle : found.title.strip(),
                blank(found.artist) ? null : found.artist.strip(), blank(found.album) ? null : found.album.strip(),
                found.millis, cover);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** What the tags held, filled in as each is found; the first of each wins. */
    static final class Found {
        String title;
        String artist;
        String albumArtist;
        String album;
        long millis = -1;
        byte[] picture;
        boolean pictureIsFront;

        void text(String key, String value) {
            switch (key.toUpperCase(Locale.ROOT)) {
                case "TITLE", "TIT2", "TT2" -> title = title == null ? value : title;
                case "ARTIST", "TPE1", "TP1" -> artist = artist == null ? value : artist;
                case "ALBUMARTIST", "ALBUM ARTIST", "TPE2", "TP2" -> albumArtist = albumArtist == null ? value
                        : albumArtist;
                case "ALBUM", "TALB", "TAL" -> album = album == null ? value : album;
                default -> {
                    // Not shown.
                }
            }
        }

        /** Keeps the front cover over any other picture, and the first of either. */
        void picture(byte[] data, int type) {
            boolean front = type == 3;

            if (data != null && data.length > 0 && (picture == null || (front && !pictureIsFront))) {
                picture = data;
                pictureIsFront = front;
            }
        }
    }

    // -------------------------------------------------------------- MP3

    private static void mp3(Path file, Found found) throws IOException {
        long size = Files.size(file);
        long tagSize = 0;

        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(10);

            if (head.length == 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                int length = syncsafe(head, 6);
                tagSize = 10 + length + ((head[5] & 0x10) != 0 ? 10 : 0);

                if (length <= MOST) {
                    id3(head[3], head[5], in.readNBytes(length), found);
                }
            }
        }

        if (found.artist == null) {
            found.artist = found.albumArtist;
        }

        found.millis = mp3Length(file, size - tagSize);
    }

    /** How long, from the first frame: its Xing or VBRI count when it has one, else its bit rate. */
    private static long mp3Length(Path file, long audioBytes) {
        try (InputStream in = Files.newInputStream(file)) {
            javazoom.jl.decoder.Bitstream bitstream = new javazoom.jl.decoder.Bitstream(in);

            try {
                javazoom.jl.decoder.Header header = bitstream.readFrame();
                return header == null ? -1 : Math.round(header.total_ms((int) Math.min(audioBytes, Integer.MAX_VALUE)));
            } finally {
                bitstream.close();
            }
        } catch (Exception | LinkageError e) {
            return -1;
        }
    }

    /** An ID3v2 tag's frames, from version 2.2 to 2.4. */
    static void id3(int version, int flags, byte[] tag, Found found) {
        if (version < 2 || version > 4) {
            return;
        }

        if (version < 4 && (flags & 0x80) != 0) {
            tag = resync(tag, 0, tag.length);
        }

        int at = 0;

        if ((flags & 0x40) != 0 && version >= 3 && tag.length >= 4) {
            // An extended header: its size counts itself in 2.4, not in 2.3.
            at = version == 4 ? syncsafe(tag, 0) : 4 + int32(tag, 0);
        }

        int idLength = version == 2 ? 3 : 4;
        int headerLength = version == 2 ? 6 : 10;

        while (at + headerLength <= tag.length && tag[at] != 0) {
            String id = new String(tag, at, idLength, StandardCharsets.ISO_8859_1);
            int length = version == 2 ? int24(tag, at + 3) : version == 3 ? int32(tag, at + 4) : syncsafe(tag, at + 4);
            int frameFlags = version == 2 ? 0 : (tag[at + 9] & 0xFF);
            int start = at + headerLength;
            at = start + length;

            if (length <= 0 || at > tag.length) {
                break;
            }

            if (version == 4 && (frameFlags & 0x0C) != 0) {
                continue; // compressed or encrypted
            }

            if (version == 3 && (frameFlags & 0xC0) != 0) {
                continue;
            }

            byte[] body = java.util.Arrays.copyOfRange(tag, start, at);

            if (version == 4 && (frameFlags & 0x01) != 0) {
                body = java.util.Arrays.copyOfRange(body, Math.min(4, body.length), body.length);
            }

            if (version == 4 && (frameFlags & 0x02) != 0) {
                body = resync(body, 0, body.length);
            }

            if (body.length == 0) {
                continue;
            }

            if (id.startsWith("T") && !id.equals("TXXX") && !id.equals("TXX")) {
                String text = decode(body, 1, body.length, body[0]);
                int end = text.indexOf('\0');
                found.text(id, end >= 0 ? text.substring(0, end) : text);
            } else if (id.equals("APIC") || id.equals("PIC")) {
                int encoding = body[0];
                int p = 1;

                if (id.equals("PIC")) {
                    p += 3; // "JPG" or "PNG"
                } else {
                    while (p < body.length && body[p] != 0) {
                        p++;
                    }

                    p++; // the MIME type and its end
                }

                if (p >= body.length) {
                    continue;
                }

                int type = body[p++] & 0xFF;
                p = afterText(body, p, encoding);

                if (p < body.length) {
                    found.picture(java.util.Arrays.copyOfRange(body, p, body.length), type);
                }
            }
        }
    }

    /** Undoes ID3's unsynchronisation: a 0 put after every 0xFF. */
    private static byte[] resync(byte[] data, int from, int to) {
        byte[] out = new byte[to - from];
        int length = 0;

        for (int i = from; i < to; i++) {
            out[length++] = data[i];

            if ((data[i] & 0xFF) == 0xFF && i + 1 < to && data[i + 1] == 0) {
                i++;
            }
        }

        return java.util.Arrays.copyOf(out, length);
    }

    /** Where text in the given ID3 encoding ends, past its terminator. */
    private static int afterText(byte[] body, int p, int encoding) {
        if (encoding == 1 || encoding == 2) {
            while (p + 1 < body.length && (body[p] != 0 || body[p + 1] != 0)) {
                p += 2;
            }

            return p + 2;
        }

        while (p < body.length && body[p] != 0) {
            p++;
        }

        return p + 1;
    }

    private static String decode(byte[] data, int from, int to, int encoding) {
        Charset charset = switch (encoding) {
            case 1 -> StandardCharsets.UTF_16;
            case 2 -> StandardCharsets.UTF_16BE;
            case 3 -> StandardCharsets.UTF_8;
            default -> StandardCharsets.ISO_8859_1;
        };
        return new String(data, from, Math.max(0, to - from), charset).replace("﻿", "");
    }

    // ------------------------------------------------------------- FLAC

    private static void flac(Path file, Found found) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);

            if (head.length == 4 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                byte[] rest = in.readNBytes(6);
                int length = syncsafe(rest, 2);
                in.skipNBytes(length + ((rest[1] & 0x10) != 0 ? 10 : 0));
                head = in.readNBytes(4);
            }

            if (!new String(head, StandardCharsets.ISO_8859_1).equals("fLaC")) {
                return;
            }

            boolean last;

            do {
                byte[] header = in.readNBytes(4);

                if (header.length < 4) {
                    return;
                }

                last = (header[0] & 0x80) != 0;
                int type = header[0] & 0x7F;
                int length = int24(header, 1);

                if (type == 0 || type == 4 || type == 6) {
                    if (length > MOST) {
                        return;
                    }

                    byte[] block = in.readNBytes(length);

                    if (type == 0 && block.length >= 18) {
                        int rate = (block[10] & 0xFF) << 12 | (block[11] & 0xFF) << 4 | (block[12] & 0xF0) >> 4;
                        long samples = (block[13] & 0x0FL) << 32 | (int32(block, 14) & 0xFFFFFFFFL);

                        if (rate > 0 && samples > 0) {
                            found.millis = samples * 1000 / rate;
                        }
                    } else if (type == 4) {
                        vorbisComments(block, 0, found);
                    } else if (type == 6) {
                        flacPicture(block, found);
                    }
                } else {
                    in.skipNBytes(length);
                }
            } while (!last);
        }

        if (found.artist == null) {
            found.artist = found.albumArtist;
        }
    }

    /** A FLAC PICTURE block, which Ogg files also carry, base64'd, in their comments. */
    static void flacPicture(byte[] block, Found found) {
        ByteBuffer data = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
        int type = data.getInt();
        skip(data, data.getInt()); // MIME type
        skip(data, data.getInt()); // description
        skip(data, 16); // width, height, depth, colours
        int length = data.getInt();

        if (length > 0 && length <= data.remaining()) {
            byte[] picture = new byte[length];
            data.get(picture);
            found.picture(picture, type);
        }
    }

    /** Vorbis comments: {@code KEY=value}, with lengths little-endian. */
    static void vorbisComments(byte[] block, int from, Found found) {
        ByteBuffer data = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        data.position(from);
        skip(data, data.getInt()); // vendor
        long count = data.getInt() & 0xFFFFFFFFL;

        for (long i = 0; i < count && data.remaining() >= 4; i++) {
            int length = data.getInt();

            if (length < 0 || length > data.remaining()) {
                return;
            }

            String comment = new String(block, data.position(), length, StandardCharsets.UTF_8);
            data.position(data.position() + length);
            int equals = comment.indexOf('=');

            if (equals <= 0) {
                continue;
            }

            String key = comment.substring(0, equals).toUpperCase(Locale.ROOT);
            String value = comment.substring(equals + 1);

            try {
                if (key.equals("METADATA_BLOCK_PICTURE")) {
                    flacPicture(Base64.getMimeDecoder().decode(value), found);
                } else if (key.equals("COVERART")) {
                    found.picture(Base64.getMimeDecoder().decode(value), 3);
                } else {
                    found.text(key, value);
                }
            } catch (RuntimeException e) {
                // One bad comment, not the rest.
            }
        }
    }

    /**
     * Moves past {@code bytes}. (Not {@code position(position() + getInt())}: Java reads
     * the position before the length moves it.)
     */
    private static void skip(ByteBuffer data, int bytes) {
        data.position(data.position() + bytes);
    }

    // -------------------------------------------------------------- Ogg

    private static void ogg(Path file, Found found) throws IOException {
        int rate = 0;
        int serial = 0;

        try (InputStream in = Files.newInputStream(file)) {
            // The first two packets of the stream: the identification header, then the comments.
            java.io.ByteArrayOutputStream packet = new java.io.ByteArrayOutputStream();
            int packets = 0;
            long read = 0;

            while (packets < 2 && read < MOST) {
                byte[] header = in.readNBytes(27);

                if (header.length < 27 || header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
                    break;
                }

                int pageSerial = int32le(header, 14);

                if (read == 0) {
                    serial = pageSerial;
                }

                byte[] lacing = in.readNBytes(header[26] & 0xFF);
                read += 27 + lacing.length;

                for (byte lace : lacing) {
                    int size = lace & 0xFF;
                    byte[] segment = in.readNBytes(size);
                    read += size;

                    if (pageSerial != serial) {
                        continue;
                    }

                    packet.write(segment);

                    if (size < 255) {
                        byte[] done = packet.toByteArray();
                        packet.reset();

                        if (packets == 0 && done.length >= 16 && done[0] == 1) {
                            rate = int32le(done, 12);
                        } else if (packets == 1 && done.length > 7 && done[0] == 3) {
                            vorbisComments(done, 7, found);
                        }

                        packets++;
                    }
                }
            }
        }

        if (found.artist == null) {
            found.artist = found.albumArtist;
        }

        if (rate > 0) {
            // The last page's position is the song's length in samples.
            try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                long size = channel.size();
                int tail = (int) Math.min(size, 65_536);
                ByteBuffer end = ByteBuffer.allocate(tail);
                channel.position(size - tail);

                while (end.hasRemaining() && channel.read(end) > 0) {
                    // Filling.
                }

                byte[] bytes = end.array();

                for (int i = tail - 27; i >= 0; i--) {
                    if (bytes[i] == 'O' && bytes[i + 1] == 'g' && bytes[i + 2] == 'g' && bytes[i + 3] == 'S'
                            && int32le(bytes, i + 14) == serial) {
                        long granule = ByteBuffer.wrap(bytes, i + 6, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();

                        if (granule > 0) {
                            found.millis = granule * 1000 / rate;
                        }

                        break;
                    }
                }
            }
        }
    }

    // -------------------------------------------------------- WAV, AIFF

    private static void sampled(Path file, Found found) throws IOException {
        try {
            AudioFileFormat format = AudioSystem.getAudioFileFormat(file.toFile());

            if (format.getFrameLength() > 0 && format.getFormat().getFrameRate() > 0) {
                found.millis = Math.round(format.getFrameLength() * 1000.0 / format.getFormat().getFrameRate());
            }
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            // No length, then.
        }
    }

    // ---------------------------------------------------------- pictures

    /** A picture beside the song: one named like it, or the folder's cover. */
    private static int[] besideIt(Path file) {
        Path folder = file.toAbsolutePath().getParent();

        if (folder == null) {
            return null;
        }

        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);

        try (var files = Files.list(folder)) {
            List<Path> pictures = files.filter(path -> PICTURE_ENDINGS.contains(Track.ending(path))).toList();

            for (String wanted : java.util.stream.Stream.concat(java.util.stream.Stream.of(base),
                    PICTURE_NAMES.stream()).toList()) {
                for (Path picture : pictures) {
                    String pictureName = picture.getFileName().toString().toLowerCase(Locale.ROOT);

                    if (pictureName.substring(0, pictureName.lastIndexOf('.')).equals(wanted)
                            && Files.size(picture) <= MOST) {
                        int[] cover = picture(Files.readAllBytes(picture));

                        if (cover != null) {
                            return cover;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // No picture, then.
        }

        return null;
    }

    /** A PNG or JPEG, cut to a square from its middle and shrunk to {@link #COVER} pixels across. */
    static int[] picture(byte[] data) {
        BufferedImage image;

        try {
            image = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException | LinkageError e) {
            return null;
        }

        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return null;
        }

        int side = Math.min(image.getWidth(), image.getHeight());
        int left = (image.getWidth() - side) / 2;
        int top = (image.getHeight() - side) / 2;
        int[] out = new int[COVER * COVER];

        for (int y = 0; y < COVER; y++) {
            int y0 = top + y * side / COVER;
            int y1 = Math.max(y0 + 1, top + (y + 1) * side / COVER);

            for (int x = 0; x < COVER; x++) {
                int x0 = left + x * side / COVER;
                int x1 = Math.max(x0 + 1, left + (x + 1) * side / COVER);
                // The average of the pixels it covers, sampled at most 6 × 6 for a huge picture.
                int stepX = Math.max(1, (x1 - x0) / 6);
                int stepY = Math.max(1, (y1 - y0) / 6);
                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;

                for (int sy = y0; sy < y1; sy += stepY) {
                    for (int sx = x0; sx < x1; sx += stepX) {
                        int argb = image.getRGB(sx, sy);
                        a += argb >>> 24;
                        r += argb >> 16 & 0xFF;
                        g += argb >> 8 & 0xFF;
                        b += argb & 0xFF;
                        n++;
                    }
                }

                out[y * COVER + x] = (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }

        return out;
    }

    // ------------------------------------------------------------ numbers

    private static int syncsafe(byte[] data, int at) {
        return (data[at] & 0x7F) << 21 | (data[at + 1] & 0x7F) << 14 | (data[at + 2] & 0x7F) << 7
                | (data[at + 3] & 0x7F);
    }

    private static int int32(byte[] data, int at) {
        return (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8
                | (data[at + 3] & 0xFF);
    }

    private static int int24(byte[] data, int at) {
        return (data[at] & 0xFF) << 16 | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF);
    }

    private static int int32le(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16
                | (data[at + 3] & 0xFF) << 24;
    }
}
