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
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** The profile service, against a stand-in: reading, wearing a skin, capes on and off, and other players' skins. */
class ProfileTest {
    private HttpServer server;
    private MinecraftProfile profile;
    private volatile String activeCape = "cape-1";
    private volatile String lastUpload = "";
    private volatile String lastAuthorization = "";
    private final Account account = new Account("0123456789abcdef0123456789abcdef", "Steve", null, "mc-token",
            Long.MAX_VALUE / 2, "refresh");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/minecraft/profile", exchange -> {
            lastAuthorization = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] body = exchange.getRequestBody().readAllBytes();

            if (path.endsWith("/skins") && method.equals("POST")) {
                lastUpload = new String(body, StandardCharsets.ISO_8859_1);
            } else if (path.endsWith("/capes/active") && method.equals("PUT")) {
                activeCape = new String(body, StandardCharsets.UTF_8).replaceAll(".*\"capeId\":\"([^\"]+)\".*", "$1");
            } else if (path.endsWith("/capes/active") && method.equals("DELETE")) {
                activeCape = null;
                answer(exchange, 200, "{}");
                return;
            }

            answer(exchange, 200, """
                    {"id":"0123456789abcdef0123456789abcdef","name":"Steve",
                     "skins":[{"id":"s1","state":"ACTIVE","url":"http://textures/steve","variant":"SLIM"}],
                     "capes":[{"id":"cape-1","state":"%s","url":"http://textures/c1","alias":"Migrator"},
                              {"id":"cape-2","state":"%s","url":"http://textures/c2","alias":"Pan"}]}"""
                    .formatted("cape-1".equals(activeCape) ? "ACTIVE" : "INACTIVE",
                            "cape-2".equals(activeCape) ? "ACTIVE" : "INACTIVE"));
        });
        server.createContext("/users/profiles/minecraft/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/Alex")) {
                answer(exchange, 200, "{\"id\":\"abc\",\"name\":\"Alex\"}");
            } else {
                answer(exchange, 404, "");
            }
        });
        server.createContext("/session/minecraft/profile/", exchange -> {
            String textures = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":"
                    + "\"http://textures/alex\",\"metadata\":{\"model\":\"slim\"}}}}").getBytes(StandardCharsets.UTF_8));
            answer(exchange, 200, "{\"id\":\"abc\",\"name\":\"Alex\",\"properties\":[{\"name\":\"textures\","
                    + "\"value\":\"" + textures + "\"}]}");
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        profile = new MinecraftProfile(new MinecraftProfile.Endpoints(base + "/minecraft/profile",
                base + "/users/profiles/minecraft/", base + "/session/minecraft/profile/"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);

        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @Test
    void readsTheSkinAndCapes() throws IOException {
        MinecraftProfile.State state = profile.get(account);
        assertEquals("Bearer mc-token", lastAuthorization);
        assertEquals("http://textures/steve", state.skin().url());
        assertTrue(state.skin().slim());
        assertEquals(2, state.capes().size());
        assertEquals("Migrator", state.activeCape().alias());
    }

    @Test
    void wearsASkinAsAPngWithItsArms() throws IOException {
        byte[] png = { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };
        profile.uploadSkin(account, png, true);
        assertTrue(lastUpload.contains("name=\"variant\"\r\n\r\nslim\r\n"), lastUpload);
        assertTrue(lastUpload.contains("filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n\u0089PNG"),
                lastUpload);
    }

    @Test
    void capesGoOnAndOff() throws IOException {
        assertEquals("Pan", profile.showCape(account, "cape-2").activeCape().alias());
        assertNull(profile.hideCape(account).activeCape());
    }

    @Test
    void anotherPlayersSkinByName() throws IOException {
        MinecraftProfile.Skin alex = profile.skinOf("Alex");
        assertEquals("http://textures/alex", alex.url());
        assertTrue(alex.slim());
        assertNull(profile.skinOf("Nobody"));
    }

    @Test
    void refusalsAreExplained() {
        server.removeContext("/minecraft/profile");
        server.createContext("/minecraft/profile", exchange -> answer(exchange, 400,
                "{\"errorMessage\":\"Invalid skin\"}"));
        IOException failure = assertThrows(IOException.class, () -> profile.uploadSkin(account, new byte[1], false));
        assertTrue(failure.getMessage().contains("64×64"), failure.getMessage());
        assertFalse(failure.getMessage().contains("HTTP"));
    }
}
