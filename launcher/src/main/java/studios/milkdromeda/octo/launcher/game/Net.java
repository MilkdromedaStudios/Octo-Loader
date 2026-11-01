package studios.milkdromeda.octo.launcher.game;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Downloads, each checked against the hash it is published with. */
public final class Net {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static volatile String userAgent = "OctoLauncher";

    private Net() {
    }

    public static void userAgent(String value) {
        userAgent = value;
    }

    public static String userAgent() {
        return userAgent;
    }

    public static HttpClient client() {
        return CLIENT;
    }

    public static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", userAgent);
    }

    /** A JSON document, read whole. */
    public static JsonObject json(String url) throws IOException {
        return JsonParser.parseString(text(url)).getAsJsonObject();
    }

    public static String text(String url) throws IOException {
        try {
            HttpResponse<String> response = CLIENT.send(request(url).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Reads JSON from a file, or {@code null} if it is not there or not JSON. */
    public static JsonObject readJson(Path file) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Downloads to {@code target}, through a {@code .part} file, and checks
     * the SHA-1 when one is given. Tried three times before giving up.
     *
     * @param counter bytes are added to it as they arrive, for progress
     */
    public static void download(String url, Path target, String sha1, AtomicLong counter) throws IOException {
        IOException last = null;

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                downloadOnce(url, target, sha1, counter);
                return;
            } catch (IOException e) {
                last = e;
            }
        }

        throw last;
    }

    private static void downloadOnce(String url, Path target, String sha1, AtomicLong counter) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest digest = sha1Digest();
        long counted = 0;

        try {
            HttpResponse<InputStream> response = CLIENT.send(request(url).timeout(Duration.ofMinutes(10)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " from " + url);
                }

                try (OutputStream out = Files.newOutputStream(part)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;

                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        digest.update(buffer, 0, read);

                        if (counter != null) {
                            counter.addAndGet(read);
                            counted += read;
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed(part, counter, counted);
            throw new IOException("interrupted", e);
        } catch (IOException e) {
            failed(part, counter, counted);
            throw e;
        }

        String got = HexFormat.of().formatHex(digest.digest());

        if (sha1 != null && !sha1.isBlank() && !sha1.equalsIgnoreCase(got)) {
            failed(part, counter, counted);
            throw new IOException(url + " did not match its checksum (expected " + sha1 + ", got " + got + ")");
        }

        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** A download that did not finish: its part file goes, and its bytes come off the progress. */
    private static void failed(Path part, AtomicLong counter, long counted) throws IOException {
        Files.deleteIfExists(part);

        if (counter != null) {
            counter.addAndGet(-counted);
        }
    }

    /** The file's SHA-1, in hex. */
    public static String sha1(Path file) throws IOException {
        MessageDigest digest = sha1Digest();

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;

            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Whether a file already there is the one wanted: the right size, and with
     * {@code thorough}, the right SHA-1 too.
     */
    public static boolean intact(Path file, long size, String sha1, boolean thorough) {
        try {
            if (!Files.isRegularFile(file) || (size > 0 && Files.size(file) != size)) {
                return false;
            }

            return !thorough || sha1 == null || sha1.isBlank() || sha1.equalsIgnoreCase(sha1(file));
        } catch (IOException e) {
            return false;
        }
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
