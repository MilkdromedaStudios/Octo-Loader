package studios.milkdromeda.octo.launcher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Sign-in against stand-ins for Microsoft, Xbox Live and Minecraft's services. */
class AuthTest {
    private HttpServer server;
    private MicrosoftAuth.Endpoints endpoints;
    private final AtomicInteger polls = new AtomicInteger();
    private volatile String profileAnswer;
    private volatile String entitlements = "{\"items\":[]}";
    private volatile String xsts = "{\"Token\":\"xsts-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}";
    private volatile int xstsStatus = 200;
    private volatile String lastIdentityToken;

    @TempDir
    Path home;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/devicecode", exchange -> answer(exchange, 200, """
                {"user_code":"ABCD1234","device_code":"device","verification_uri":"https://www.microsoft.com/link",
                 "interval":1,"expires_in":60}"""));
        server.createContext("/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            if (form.contains("grant_type=refresh_token")) {
                answer(exchange, 200, "{\"access_token\":\"ms-access-2\",\"refresh_token\":\"ms-refresh-2\"}");
            } else if (polls.incrementAndGet() < 2) {
                answer(exchange, 400, "{\"error\":\"authorization_pending\"}");
            } else {
                answer(exchange, 200, "{\"access_token\":\"ms-access\",\"refresh_token\":\"ms-refresh\"}");
            }
        });
        server.createContext("/xbox", exchange -> answer(exchange, 200,
                "{\"Token\":\"xbl-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}"));
        server.createContext("/xsts", exchange -> answer(exchange, xstsStatus, xsts));
        server.createContext("/login", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastIdentityToken = com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("identityToken")
                    .getAsString();
            answer(exchange, 200, "{\"access_token\":\"mc-token\",\"expires_in\":86400}");
        });
        server.createContext("/profile", exchange -> {
            if (profileAnswer == null) {
                answer(exchange, 404, "{\"error\":\"NOT_FOUND\"}");
            } else {
                answer(exchange, 200, profileAnswer);
            }
        });
        server.createContext("/entitlements", exchange -> answer(exchange, 200, entitlements));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        endpoints = new MicrosoftAuth.Endpoints(base + "/devicecode", base + "/token", base + "/xbox", base + "/xsts",
                base + "/login", base + "/profile", base + "/entitlements");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void signsInThroughXboxLiveToMinecraft() throws IOException {
        profileAnswer = """
                {"id":"0123456789abcdef0123456789abcdef","name":"Steve",
                 "skins":[{"state":"INACTIVE","url":"http://old"},{"state":"ACTIVE","url":"http://skin"}]}""";
        MicrosoftAuth auth = new MicrosoftAuth("client", endpoints);
        MicrosoftAuth.DeviceCode code = auth.start();
        assertEquals("ABCD1234", code.userCode());
        assertEquals("https://www.microsoft.com/link?otc=ABCD1234", code.linkWithCode());

        Account account = auth.finish(code, () -> false);

        assertEquals("Steve", account.name());
        assertEquals("0123456789abcdef0123456789abcdef", account.id());
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", account.dashedId());
        assertEquals("mc-token", account.accessToken());
        assertEquals("ms-refresh", account.refreshToken());
        assertEquals("http://skin", account.skinUrl());
        assertTrue(account.fresh());
        assertEquals("XBL3.0 x=hash;xsts-token", lastIdentityToken);
        assertTrue(polls.get() >= 2, "waited while the player was still signing in");

        Account refreshed = auth.refresh(account);
        assertEquals("ms-refresh-2", refreshed.refreshToken(), "a refresh brings a new refresh token");
    }

    @Test
    void anAccountWithoutMinecraftIsToldSo() throws IOException {
        MicrosoftAuth auth = new MicrosoftAuth("client", endpoints);
        IOException failure = assertThrows(IOException.class, () -> auth.finish(auth.start(), () -> false));
        assertTrue(failure.getMessage().contains("does not own Minecraft"), failure.getMessage());

        entitlements = "{\"items\":[{\"name\":\"product_minecraft\"},{\"name\":\"game_minecraft\"}]}";
        polls.set(5);
        IOException noName = assertThrows(IOException.class, () -> auth.finish(auth.start(), () -> false));
        assertTrue(noName.getMessage().contains("not chosen a name"), noName.getMessage());
    }

    @Test
    void xboxRefusalsAreExplained() {
        xstsStatus = 401;
        xsts = "{\"XErr\":2148916233}";
        MicrosoftAuth auth = new MicrosoftAuth("client", endpoints);
        IOException failure = assertThrows(IOException.class, () -> auth.finish(auth.start(), () -> false));
        assertTrue(failure.getMessage().contains("no Xbox profile"), failure.getMessage());
        assertTrue(MicrosoftAuth.failure("Xbox Live", 401, com.google.gson.JsonParser.parseString(
                "{\"XErr\":2148916238}").getAsJsonObject()).contains("Family"));
    }

    @Test
    void cancellingStopsWaiting() {
        MicrosoftAuth auth = new MicrosoftAuth("client", endpoints);
        IOException failure = assertThrows(IOException.class, () -> auth.finish(auth.start(), () -> true));
        assertTrue(failure.getMessage().contains("cancelled"));
    }

    @Test
    void accountsAreKeptForThePlayerAloneAndForgottenOnSignOut() throws IOException {
        Accounts accounts = new Accounts(home);
        assertNull(accounts.active());
        long later = Instant.now().getEpochSecond() + 3600;
        accounts.put(new Account("a".repeat(32), "Alex", null, "t1", later, "r1"));
        accounts.put(new Account("b".repeat(32), "Steve", null, "t2", later, "r2"));

        Accounts again = new Accounts(home);
        assertEquals("Steve", again.active().name(), "the newest sign-in is the one that plays");
        assertEquals(2, again.all().size());
        again.use("a".repeat(32));
        assertEquals("Alex", new Accounts(home).active().name());

        Path file = home.resolve("accounts.json");

        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }

        again.remove("a".repeat(32));
        assertEquals("Steve", again.active().name());
        assertFalse(Files.readString(file).contains("r1"), "a signed-out account's tokens leave the file");
        assertEquals(List.of("Steve"), new Accounts(home).all().stream().map(Account::name).toList());
    }

    @Test
    void anOldSessionIsNotFresh() {
        assertFalse(new Account("c".repeat(32), "Old", null, "t", Instant.now().getEpochSecond() + 60, "r").fresh(),
                "a session about to run out is refreshed before a game starts");
    }
}
